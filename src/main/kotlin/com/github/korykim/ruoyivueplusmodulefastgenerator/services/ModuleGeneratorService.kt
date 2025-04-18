package com.github.korykim.ruoyivueplusmodulefastgenerator.services

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.XmlElementFactory
import com.intellij.lang.xml.XMLLanguage
import org.jetbrains.idea.maven.project.MavenProjectsManager


/**
 * 模块生成服务
 * 负责处理文件修改和模块创建等操作
 */
@Service(Service.Level.PROJECT)
class ModuleGeneratorService(private val project: Project) {
    
    private val logger = logger<ModuleGeneratorService>()
    
    /**
     * 生成新模块
     *
     * @param moduleName 模块名称，格式如 "ruoyi-xxx"
     * @param dependencyConfigName 依赖配置名称，用于获取自定义依赖内容
     * @param modulePrefix 模块前缀，默认为 null（使用配置服务中的前缀）
     * @return 操作是否成功
     */
    fun generateModule(moduleName: String, dependencyConfigName: String? = null, modulePrefix: String? = null): Boolean {
        try {
            // 检查模块名称格式
            val normalizedModuleName = normalizeModuleName(moduleName, modulePrefix)
            
            // 1. 修改根目录 pom.xml
            updateRootPom(normalizedModuleName)
            
            // 2. 修改 ruoyi-modules/pom.xml
            updateModulesPom(normalizedModuleName)
            
            // 3. 修改 ruoyi-admin/pom.xml
            updateAdminPom(normalizedModuleName)
            
            // 4. 创建新模块目录和文件
            @Suppress("UNUSED_VARIABLE")
            val moduleDir = createModuleStructure(normalizedModuleName, dependencyConfigName)
            
            // 5. 刷新项目视图
            refreshProjectView()
            
            // 6. 显式触发Maven项目导入
            importMavenChanges()
            
            return true
        } catch (e: Exception) {
            logger.error("生成模块失败: ${e.message}", e)
            return false
        }
    }
    
    /**
     * 标准化模块名称，确保格式为 "[prefix]xxx"
     * 
     * @param moduleName 用户输入的模块名称
     * @param modulePrefix 模块前缀，如果为null则使用配置服务中的默认前缀
     * @return 标准化后的模块名称
     */
    private fun normalizeModuleName(moduleName: String, modulePrefix: String? = null): String {
        val prefix = modulePrefix ?: DependencyConfigService.getInstance().modulePrefix
        var name = moduleName.trim()
        if (!name.startsWith(prefix)) {
            name = "$prefix$name"
        }
        return name
    }
    
    /**
     * 更新根目录 pom.xml 文件
     */
    private fun updateRootPom(moduleName: String) {
        val rootPomFile = findFileInProject("pom.xml") ?: throw Exception("根目录 pom.xml 文件未找到")
        
        val psiFile = PsiManager.getInstance(project).findFile(rootPomFile) as? XmlFile
            ?: throw Exception("无法解析 pom.xml 文件")
            
        WriteCommandAction.runWriteCommandAction(project, "Add Dependency to Root Pom.xml", null, {
            try {
                // 查找 <dependencyManagement> 元素
                val rootTag = psiFile.rootTag ?: throw Exception("无法找到 pom.xml 根元素")
                val dependencyManagementTag = rootTag.findFirstSubTag("dependencyManagement")
                    ?: throw Exception("在根 pom.xml 中未找到 dependencyManagement 标签")
                    
                val dependenciesTag = dependencyManagementTag.findFirstSubTag("dependencies")
                    ?: throw Exception("在 dependencyManagement 中未找到 dependencies 标签")
                    
                // 创建新的依赖元素
                val dependencyXml = """
                    <dependency>
                        <groupId>org.dromara</groupId>
                        <artifactId>$moduleName</artifactId>
                        <version>${"$"}{revision}</version>
                    </dependency>
                """.trimIndent()
                
                // 添加到最后一个 dependency 标签后
                val factory = XmlElementFactory.getInstance(project)
                val newDependencyTag = factory.createTagFromText(dependencyXml, XMLLanguage.INSTANCE)
                dependenciesTag.addSubTag(newDependencyTag, false)
            } catch (e: Exception) {
                logger.error("更新根 pom.xml 失败", e)
                throw e
            }
        })
    }
    
    /**
     * 更新 ruoyi-modules/pom.xml 文件
     */
    private fun updateModulesPom(moduleName: String) {
        val modulesPomFile = findFileInProject("ruoyi-modules/pom.xml")
            ?: throw Exception("ruoyi-modules/pom.xml 文件未找到")
            
        val psiFile = PsiManager.getInstance(project).findFile(modulesPomFile) as? XmlFile
            ?: throw Exception("无法解析 ruoyi-modules/pom.xml 文件")
            
        WriteCommandAction.runWriteCommandAction(project, "Add Module to Modules Pom.xml", null, {
            try {
                val rootTag = psiFile.rootTag ?: throw Exception("无法找到 pom.xml 根元素")
                val modulesTag = rootTag.findFirstSubTag("modules")
                    ?: throw Exception("在 modules pom.xml 中未找到 modules 标签")
                    
                // 创建新的模块元素
                val moduleXml = "<module>$moduleName</module>"
                val factory = XmlElementFactory.getInstance(project)
                val newModuleTag = factory.createTagFromText(moduleXml, XMLLanguage.INSTANCE)
                modulesTag.addSubTag(newModuleTag, false)
            } catch (e: Exception) {
                logger.error("更新 modules pom.xml 失败", e)
                throw e
            }
        })
    }
    
    /**
     * 更新 ruoyi-admin/pom.xml 文件
     */
    private fun updateAdminPom(moduleName: String) {
        val adminPomFile = findFileInProject("ruoyi-admin/pom.xml")
            ?: throw Exception("ruoyi-admin/pom.xml 文件未找到")
            
        val psiFile = PsiManager.getInstance(project).findFile(adminPomFile) as? XmlFile
            ?: throw Exception("无法解析 ruoyi-admin/pom.xml 文件")
            
        WriteCommandAction.runWriteCommandAction(project, "Add Dependency to Admin Pom.xml", null, {
            try {
                val rootTag = psiFile.rootTag ?: throw Exception("无法找到 pom.xml 根元素")
                val dependenciesTag = rootTag.findFirstSubTag("dependencies")
                    ?: throw Exception("在 admin pom.xml 中未找到 dependencies 标签")
                    
                // 查找版本号
                val version = findProjectVersion()
                
                // 创建新的依赖元素，显式包含版本号
                val simpleModuleName = moduleName.substringAfter("ruoyi-")
                val dependencyXml = """
                    <!-- ${simpleModuleName}模块  -->
                    <dependency>
                        <groupId>org.dromara</groupId>
                        <artifactId>$moduleName</artifactId>
                        <version>$version</version>
                    </dependency>
                """.trimIndent()
                
                val factory = XmlElementFactory.getInstance(project)
                val newDependencyTag = factory.createTagFromText(dependencyXml, XMLLanguage.INSTANCE)
                dependenciesTag.addSubTag(newDependencyTag, false)
            } catch (e: Exception) {
                logger.error("更新 admin pom.xml 失败", e)
                throw e
            }
        })
    }
    
    /**
     * 查找项目版本号
     */
    private fun findProjectVersion(): String {
        val rootPomFile = findFileInProject("pom.xml") ?: throw Exception("根目录 pom.xml 文件未找到")
        
        val psiFile = PsiManager.getInstance(project).findFile(rootPomFile) as? XmlFile
            ?: throw Exception("无法解析 pom.xml 文件")
            
        val rootTag = psiFile.rootTag ?: throw Exception("无法找到 pom.xml 根元素")
        
        // 首先尝试读取<version>标签
        val versionTag = rootTag.findFirstSubTag("version")
        if (versionTag != null) {
            return versionTag.value.text
        }
        
        // 如果没有直接的version标签，尝试读取<properties>下的<revision>标签
        val propertiesTag = rootTag.findFirstSubTag("properties")
        if (propertiesTag != null) {
            val revisionTag = propertiesTag.findFirstSubTag("revision")
            if (revisionTag != null) {
                return revisionTag.value.text
            }
        }
        
        // 如果都没找到，返回一个默认值
        return "5.3.1"
    }
    
    /**
     * 创建新模块目录结构和文件
     * 
     * @param moduleName 模块名称
     * @param dependencyConfigName 依赖配置名称，如果为null则使用默认依赖
     * @return 创建的模块目录
     */
    private fun createModuleStructure(moduleName: String, dependencyConfigName: String? = null): VirtualFile {
        val modulesDir = findFileInProject("ruoyi-modules")
            ?: throw Exception("ruoyi-modules 目录未找到")
        
        var resultDir: VirtualFile? = null    
        
        WriteCommandAction.runWriteCommandAction(project, "Create Module Structure", null, {
            try {
                val newDir = modulesDir.createChildDirectory(this, moduleName)
                
                // 创建 pom.xml 文件
                val pomContent = generateModulePomContent(moduleName, dependencyConfigName)
                val pomFile = newDir.createChildData(this, "pom.xml")
                VfsUtil.saveText(pomFile, pomContent)
                
                // 创建标准 Java 项目结构
                val srcDir = newDir.createChildDirectory(this, "src")
                val mainDir = srcDir.createChildDirectory(this, "main")
                val javaDir = mainDir.createChildDirectory(this, "java")
                mainDir.createChildDirectory(this, "resources")
                
                // 创建包结构
                val orgDir = javaDir.createChildDirectory(this, "org")
                val dromaraDir = orgDir.createChildDirectory(this, "dromara")
                val moduleNameDir = dromaraDir.createChildDirectory(this, moduleName.replace("-", ""))
                
                // 创建基本文件
                createBasicPackages(moduleNameDir)
                
                resultDir = newDir
            } catch (e: Exception) {
                logger.error("创建模块目录结构失败", e)
                throw e
            }
        })
        
        return resultDir ?: throw Exception("创建模块目录失败")
    }
    
    /**
     * 创建基本包结构
     */
    private fun createBasicPackages(baseDir: VirtualFile) {
        WriteCommandAction.runWriteCommandAction(project, "Create Basic Packages", null, {
            try {
                // 创建常用的包结构
                baseDir.createChildDirectory(this, "controller")
                baseDir.createChildDirectory(this, "domain")
                baseDir.createChildDirectory(this, "mapper")
                val serviceDir = baseDir.createChildDirectory(this, "service")
                serviceDir.createChildDirectory(this, "impl")
            } catch (e: Exception) {
                logger.error("创建基本包结构失败", e)
                throw e
            }
        })
    }
    
    /**
     * 生成模块 pom.xml 内容
     * 
     * @param moduleName 模块名称
     * @param dependencyConfigName 依赖配置名称，如果为null则使用默认依赖
     * @return 生成的pom.xml内容
     */
    private fun generateModulePomContent(moduleName: String, dependencyConfigName: String? = null): String {
        val simpleModuleName = moduleName.substringAfter("ruoyi-")
        
        // 获取项目版本号
        val version = try {
            findProjectVersion()
        } catch (e: Exception) {
            "${"\$"}{revision}" // 使用默认的表达式如果找不到版本号
        }
        
        // 获取依赖配置内容
        val dependenciesContent = if (dependencyConfigName != null) {
            DependencyConfigService.getInstance().getDependenciesContent(dependencyConfigName)
        } else {
            // 如果没有指定配置或者配置不存在，使用默认配置
            DependencyConfigService.getInstance().getConfigNames().firstOrNull()?.let {
                DependencyConfigService.getInstance().getDependenciesContent(it)
            } ?: DEFAULT_DEPENDENCIES
        }
        
        return """
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <parent>
        <artifactId>ruoyi-modules</artifactId>
        <groupId>org.dromara</groupId>
        <version>$version</version>
    </parent>
    <modelVersion>4.0.0</modelVersion>

    <artifactId>${moduleName}</artifactId>

    <description>
        ${simpleModuleName}模块
    </description>

    <dependencies>
$dependenciesContent
    </dependencies>
</project>
        """.trimIndent()
    }
    
    /**
     * 在项目中查找指定路径的文件
     */
    private fun findFileInProject(relativePath: String): VirtualFile? {
        val basePath = project.basePath ?: return null
        val filePath = "$basePath/$relativePath"
        return LocalFileSystem.getInstance().findFileByPath(filePath)
    }
    
    /**
     * 刷新项目视图
     */
    private fun refreshProjectView() {
        val basePath = project.basePath ?: return
        val projectDir = LocalFileSystem.getInstance().findFileByPath(basePath)
        projectDir?.refresh(true, true)
        
        // 添加刷新Maven项目的命令
        try {
            val mavenProjectsManager = MavenProjectsManager.getInstance(project)
            mavenProjectsManager.forceUpdateAllProjectsOrFindAllAvailablePomFiles()
        } catch (e: Exception) {
            logger.error("刷新Maven项目失败: ${e.message}", e)
            // 忽略异常，不影响主流程
        }
    }
    
    /**
     * 导入Maven项目变更
     */
    private fun importMavenChanges() {
        try {
            val mavenProjectsManager = MavenProjectsManager.getInstance(project)

            // 查找新的pom文件
            mavenProjectsManager.forceUpdateAllProjectsOrFindAllAvailablePomFiles()
            
            // 等待Maven导入完成
            ApplicationManager.getApplication().invokeLater {
                try {
                    // 使用标准Maven项目刷新方法
                    // 1. 强制更新所有项目
                    mavenProjectsManager.forceUpdateAllProjectsOrFindAllAvailablePomFiles()
                    
                    // 2. 触发项目重新加载 - 使用非弃用方法组合
                    val basePath = project.basePath
                    if (basePath != null) {
                        LocalFileSystem.getInstance().findFileByPath(basePath)?.refresh(false, true)
                    }
                    
                    // 注：以下是已废弃或实验性的API，不再使用
                    // mavenProjectsManager.importProjects()
                    // mavenProjectsManager.scheduleImportAndResolve()
                    // mavenProjectsManager.scheduleUpdateAllMavenProjects(MavenSyncSpec.full("PluginSync", true))
                } catch (e: Exception) {
                    logger.error("Maven项目导入步骤失败: ${e.message}", e)
                }
            }
        } catch (e: Exception) {
            logger.error("Maven项目导入失败: ${e.message}", e)
            // 忽略异常，不影响主流程
        }
    }
    
    companion object {
        /**
         * 默认依赖内容，当没有配置或配置失效时使用
         */
        private const val DEFAULT_DEPENDENCIES = """
 
        <!-- 通用工具-->
        <dependency>
            <groupId>org.dromara</groupId>
            <artifactId>ruoyi-common-core</artifactId>
        </dependency>

        <dependency>
            <groupId>org.dromara</groupId>
            <artifactId>ruoyi-common-doc</artifactId>
        </dependency>

        <dependency>
            <groupId>org.dromara</groupId>
            <artifactId>ruoyi-common-sms</artifactId>
        </dependency>

        <dependency>
            <groupId>org.dromara</groupId>
            <artifactId>ruoyi-common-mail</artifactId>
        </dependency>

        <dependency>
            <groupId>org.dromara</groupId>
            <artifactId>ruoyi-common-redis</artifactId>
        </dependency>

        <dependency>
            <groupId>org.dromara</groupId>
            <artifactId>ruoyi-common-idempotent</artifactId>
        </dependency>

        <dependency>
            <groupId>org.dromara</groupId>
            <artifactId>ruoyi-common-mybatis</artifactId>
        </dependency>

        <dependency>
            <groupId>org.dromara</groupId>
            <artifactId>ruoyi-common-log</artifactId>
        </dependency>

        <dependency>
            <groupId>org.dromara</groupId>
            <artifactId>ruoyi-common-excel</artifactId>
        </dependency>

        <dependency>
            <groupId>org.dromara</groupId>
            <artifactId>ruoyi-common-security</artifactId>
        </dependency>

        <dependency>
            <groupId>org.dromara</groupId>
            <artifactId>ruoyi-common-web</artifactId>
        </dependency>

        <dependency>
            <groupId>org.dromara</groupId>
            <artifactId>ruoyi-common-ratelimiter</artifactId>
        </dependency>

        <dependency>
            <groupId>org.dromara</groupId>
            <artifactId>ruoyi-common-translation</artifactId>
        </dependency>

        <dependency>
            <groupId>org.dromara</groupId>
            <artifactId>ruoyi-common-sensitive</artifactId>
        </dependency>

        <dependency>
            <groupId>org.dromara</groupId>
            <artifactId>ruoyi-common-encrypt</artifactId>
        </dependency>

        <dependency>
            <groupId>org.dromara</groupId>
            <artifactId>ruoyi-common-tenant</artifactId>
        </dependency>

        <dependency>
            <groupId>org.dromara</groupId>
            <artifactId>ruoyi-common-websocket</artifactId>
        </dependency>
 
        """
    }
}

