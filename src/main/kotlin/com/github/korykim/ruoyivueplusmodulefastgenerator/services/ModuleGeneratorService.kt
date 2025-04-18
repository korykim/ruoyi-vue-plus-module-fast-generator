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
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.TimeUnit


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
            
            logger.info("开始生成模块: $normalizedModuleName")
            
            // 检查模块是否已存在
            val moduleExists = isModuleExists(normalizedModuleName)
            if (moduleExists) {
                logger.info("模块 '$normalizedModuleName' 已存在，将尝试更新现有模块")
            } else {
                logger.info("模块 '$normalizedModuleName' 不存在，将创建新模块")
            }
            
            // 1. 修改根目录 pom.xml
            updateRootPom(normalizedModuleName)
            
            // 2. 修改 ruoyi-modules/pom.xml
            updateModulesPom(normalizedModuleName)
            
            // 3. 修改 ruoyi-admin/pom.xml
            updateAdminPom(normalizedModuleName)
            
            // 4. 创建新模块目录和文件（或重用现有目录）
            val moduleDir = createModuleStructure(normalizedModuleName, dependencyConfigName)
            
            // 5. 刷新项目视图
            refreshProjectView()
            
            // 6. 显式触发Maven项目导入
            importMavenChanges()
            
            // 7. 确保新模块的pom.xml被刷新和导入
            refreshAndImportModulePom(moduleDir)
            
            // 8. 延迟刷新确保Maven项目导入完成后UI更新
            scheduleDelayedRefresh(project, normalizedModuleName)
            
            logger.info("模块 '$normalizedModuleName' " + (if (moduleExists) "更新" else "创建") + "成功")
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
                
                // 检查依赖是否已存在
                val dependencyExists = dependenciesTag.findSubTags("dependency").any { dependencyTag ->
                    val artifactIdTag = dependencyTag.findFirstSubTag("artifactId")
                    artifactIdTag?.value?.text == moduleName
                }
                
                if (!dependencyExists) {
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
                    
                    logger.info("已添加模块 '$moduleName' 的依赖到根 pom.xml")
                } else {
                    logger.info("模块 '$moduleName' 的依赖已存在于根 pom.xml 中，无需重复添加")
                }
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
        // 获取当前配置的模块前缀，并移除结尾的连字符（如果有）
        val prefix = DependencyConfigService.getInstance().modulePrefix.trimEnd('-')
        
        val modulesPomFile = findFileInProject("$prefix-modules/pom.xml")
            ?: throw Exception("$prefix-modules/pom.xml 文件未找到")
            
        val psiFile = PsiManager.getInstance(project).findFile(modulesPomFile) as? XmlFile
            ?: throw Exception("无法解析 $prefix-modules/pom.xml 文件")
            
        WriteCommandAction.runWriteCommandAction(project, "Add Module to Modules Pom.xml", null, {
            try {
                val rootTag = psiFile.rootTag ?: throw Exception("无法找到 pom.xml 根元素")
                val modulesTag = rootTag.findFirstSubTag("modules")
                    ?: throw Exception("在 modules pom.xml 中未找到 modules 标签")
                    
                // 检查模块是否已存在
                val moduleExists = modulesTag.findSubTags("module").any { moduleTag ->
                    moduleTag.value.text == moduleName
                }
                
                if (!moduleExists) {
                    // 创建新的模块元素
                    val moduleXml = "<module>$moduleName</module>"
                    val factory = XmlElementFactory.getInstance(project)
                    val newModuleTag = factory.createTagFromText(moduleXml, XMLLanguage.INSTANCE)
                    modulesTag.addSubTag(newModuleTag, false)
                    
                    logger.info("已添加模块 '$moduleName' 到 modules pom.xml")
                } else {
                    logger.info("模块 '$moduleName' 已存在于 modules pom.xml 中，无需重复添加")
                }
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
                
                // 检查依赖是否已存在
                val dependencyExists = dependenciesTag.findSubTags("dependency").any { dependencyTag ->
                    val artifactIdTag = dependencyTag.findFirstSubTag("artifactId")
                    artifactIdTag?.value?.text == moduleName
                }
                
                if (!dependencyExists) {
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
                    
                    logger.info("已添加模块 '$moduleName' 的依赖到 admin pom.xml")
                } else {
                    logger.info("模块 '$moduleName' 的依赖已存在于 admin pom.xml 中，无需重复添加")
                }
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
        // 获取当前配置的模块前缀，并移除结尾的连字符（如果有）
        val prefix = DependencyConfigService.getInstance().modulePrefix.trimEnd('-')
        
        val modulesDir = findFileInProject("$prefix-modules")
            ?: throw Exception("$prefix-modules 目录未找到")
        
        // 检查模块目录是否已存在
        val existingDir = modulesDir.findChild(moduleName)
        if (existingDir != null && existingDir.isDirectory) {
            // 模块目录已存在，可以选择重用现有目录或提示错误
            logger.info("模块目录 '$moduleName' 已存在，正在重用现有目录")
            
            // 刷新现有目录以确保获取最新状态
            existingDir.refresh(true, true)
            
            return existingDir
        }
        
        var resultDir: VirtualFile? = null    
        
        WriteCommandAction.runWriteCommandAction(project, "Create Module Structure", null, {
            try {
                // 创建新目录前再次检查目录是否存在（可能是在刷新后检测到的）
                val checkAgain = modulesDir.findChild(moduleName)
                val newDir = if (checkAgain != null && checkAgain.isDirectory) {
                    // 如果存在，使用现有目录
                    checkAgain
                } else {
                    // 如果不存在，创建新目录
                    modulesDir.createChildDirectory(this, moduleName)
                }
                
                // 检查pom.xml是否已存在
                val existingPomFile = newDir.findChild("pom.xml")
                if (existingPomFile == null) {
                    // 创建 pom.xml 文件（如果不存在）
                    val pomContent = generateModulePomContent(moduleName, dependencyConfigName)
                    val pomFile = newDir.createChildData(this, "pom.xml")
                    VfsUtil.saveText(pomFile, pomContent)
                }
                
                // 检查src目录是否已存在
                val existingSrcDir = newDir.findChild("src")
                val srcDir = if (existingSrcDir != null && existingSrcDir.isDirectory) {
                    existingSrcDir
                } else {
                    newDir.createChildDirectory(this, "src")
                }
                
                // 检查main目录是否已存在
                val existingMainDir = srcDir.findChild("main")
                val mainDir = if (existingMainDir != null && existingMainDir.isDirectory) {
                    existingMainDir
                } else {
                    srcDir.createChildDirectory(this, "main")
                }
                
                // 检查java目录是否已存在
                val existingJavaDir = mainDir.findChild("java")
                val javaDir = if (existingJavaDir != null && existingJavaDir.isDirectory) {
                    existingJavaDir
                } else {
                    mainDir.createChildDirectory(this, "java")
                }
                
                // 检查resources目录是否已存在
                if (mainDir.findChild("resources") == null) {
                    mainDir.createChildDirectory(this, "resources")
                }
                
                // 检查包结构是否已存在
                val existingOrgDir = javaDir.findChild("org")
                val orgDir = if (existingOrgDir != null && existingOrgDir.isDirectory) {
                    existingOrgDir
                } else {
                    javaDir.createChildDirectory(this, "org")
                }
                
                val existingDromaraDir = orgDir.findChild("dromara")
                val dromaraDir = if (existingDromaraDir != null && existingDromaraDir.isDirectory) {
                    existingDromaraDir
                } else {
                    orgDir.createChildDirectory(this, "dromara")
                }
                
                val moduleNameWithoutDash = moduleName.replace("-", "")
                val existingModuleNameDir = dromaraDir.findChild(moduleNameWithoutDash)
                val moduleNameDir = if (existingModuleNameDir != null && existingModuleNameDir.isDirectory) {
                    existingModuleNameDir
                } else {
                    dromaraDir.createChildDirectory(this, moduleNameWithoutDash)
                }
                
                // 创建基本包结构
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
                // 创建常用的包结构（先检查是否已存在）
                if (baseDir.findChild("controller") == null) {
                    baseDir.createChildDirectory(this, "controller")
                }
                if (baseDir.findChild("domain") == null) {
                    baseDir.createChildDirectory(this, "domain")
                }
                if (baseDir.findChild("mapper") == null) {
                    baseDir.createChildDirectory(this, "mapper")
                }
                
                val existingServiceDir = baseDir.findChild("service")
                val serviceDir = if (existingServiceDir != null && existingServiceDir.isDirectory) {
                    existingServiceDir
                } else {
                    baseDir.createChildDirectory(this, "service")
                }
                
                if (serviceDir.findChild("impl") == null) {
                    serviceDir.createChildDirectory(this, "impl")
                }
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
        val simpleModuleName = moduleName.substringAfter("${DependencyConfigService.getInstance().modulePrefix.trimEnd('-')}-")
        
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
        
        // 获取当前配置的模块前缀，并移除结尾的连字符（如果有）
        val prefix = DependencyConfigService.getInstance().modulePrefix.trimEnd('-')
        
        return """
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <parent>
        <artifactId>${prefix}-modules</artifactId>
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
    
    /**
     * 刷新并导入指定模块的pom.xml
     * 
     * @param moduleDir 模块目录
     */
    private fun refreshAndImportModulePom(moduleDir: VirtualFile) {
        try {
            // 查找模块的pom.xml文件
            val pomFile = moduleDir.findChild("pom.xml") ?: return
            
            // 刷新pom文件 - 使用异步刷新以提高性能
            pomFile.refresh(true, false)
            
            // 获取Maven项目管理器
            val mavenProjectsManager = MavenProjectsManager.getInstance(project)
            
            // 添加pom.xml文件到Maven项目
            ApplicationManager.getApplication().invokeLater {
                try {
                    // 使用非阻塞方式添加pom文件并触发导入
                    if (!mavenProjectsManager.isManagedFile(pomFile)) {
                        mavenProjectsManager.addManagedFilesOrUnignore(listOf(pomFile))
                        
                        // 强制更新所有项目，使用最新的API
                        mavenProjectsManager.forceUpdateAllProjectsOrFindAllAvailablePomFiles()
                        
                        // 确保模块目录也被刷新
                        moduleDir.refresh(true, true)
                    }
                } catch (e: Exception) {
                    logger.error("刷新模块pom.xml失败: ${e.message}", e)
                }
            }
        } catch (e: Exception) {
            logger.error("处理模块pom.xml时出错: ${e.message}", e)
        }
    }
    
    /**
     * 检查模块是否已存在
     */
    private fun isModuleExists(moduleName: String): Boolean {
        // 获取当前配置的模块前缀，并移除结尾的连字符（如果有）
        val prefix = DependencyConfigService.getInstance().modulePrefix.trimEnd('-')
        
        // 构建模块目录路径
        val modulesDir = findFileInProject("$prefix-modules") ?: return false
        val moduleDir = modulesDir.findChild(moduleName)
        return moduleDir != null && moduleDir.isDirectory
    }
    
    /**
     * 删除指定的模块
     *
     * @param moduleName 要删除的模块名称
     * @return 操作是否成功
     */
    fun deleteModule(moduleName: String): Boolean {
        try {
            logger.info("开始删除模块: $moduleName")
            
            // 检查模块是否存在
            if (!isModuleExists(moduleName)) {
                logger.info("模块 '$moduleName' 不存在，无需删除")
                return false
            }
            
            // 1. 从根目录 pom.xml 中删除依赖
            removeFromRootPom(moduleName)
            
            // 2. 从 ruoyi-modules/pom.xml 中删除模块
            removeFromModulesPom(moduleName)
            
            // 3. 从 ruoyi-admin/pom.xml 中删除依赖
            removeFromAdminPom(moduleName)
            
            // 4. 删除模块目录
            deleteModuleDirectory(moduleName)
            
            // 5. 刷新项目视图
            refreshProjectView()
            
            // 6. 显式触发Maven项目导入
            importMavenChanges()
            
            logger.info("模块 '$moduleName' 删除成功")
            return true
        } catch (e: Exception) {
            logger.error("删除模块失败: ${e.message}", e)
            return false
        }
    }
    
    /**
     * 从根目录 pom.xml 中删除依赖
     */
    private fun removeFromRootPom(moduleName: String) {
        val rootPomFile = findFileInProject("pom.xml") ?: throw Exception("根目录 pom.xml 文件未找到")
        
        val psiFile = PsiManager.getInstance(project).findFile(rootPomFile) as? XmlFile
            ?: throw Exception("无法解析 pom.xml 文件")
            
        WriteCommandAction.runWriteCommandAction(project, "Remove Dependency from Root Pom.xml", null, {
            try {
                // 查找 <dependencyManagement> 元素
                val rootTag = psiFile.rootTag ?: throw Exception("无法找到 pom.xml 根元素")
                val dependencyManagementTag = rootTag.findFirstSubTag("dependencyManagement")
                    ?: throw Exception("在根 pom.xml 中未找到 dependencyManagement 标签")
                    
                val dependenciesTag = dependencyManagementTag.findFirstSubTag("dependencies")
                    ?: throw Exception("在 dependencyManagement 中未找到 dependencies 标签")
                
                // 查找要删除的依赖
                dependenciesTag.findSubTags("dependency").forEach { dependencyTag ->
                    val artifactIdTag = dependencyTag.findFirstSubTag("artifactId")
                    if (artifactIdTag?.value?.text == moduleName) {
                        // 找到了对应的依赖，删除它
                        dependencyTag.delete()
                        logger.info("已从根 pom.xml 中删除模块 '$moduleName' 的依赖")
                    }
                }
            } catch (e: Exception) {
                logger.error("从根 pom.xml 中删除依赖失败", e)
                throw e
            }
        })
    }
    
    /**
     * 从 ruoyi-modules/pom.xml 中删除模块
     */
    private fun removeFromModulesPom(moduleName: String) {
        // 获取当前配置的模块前缀，并移除结尾的连字符（如果有）
        val prefix = DependencyConfigService.getInstance().modulePrefix.trimEnd('-')
        
        val modulesPomFile = findFileInProject("$prefix-modules/pom.xml")
            ?: throw Exception("$prefix-modules/pom.xml 文件未找到")
            
        val psiFile = PsiManager.getInstance(project).findFile(modulesPomFile) as? XmlFile
            ?: throw Exception("无法解析 $prefix-modules/pom.xml 文件")
            
        WriteCommandAction.runWriteCommandAction(project, "Remove Module from Modules Pom.xml", null, {
            try {
                val rootTag = psiFile.rootTag ?: throw Exception("无法找到 pom.xml 根元素")
                val modulesTag = rootTag.findFirstSubTag("modules")
                    ?: throw Exception("在 modules pom.xml 中未找到 modules 标签")
                    
                // 查找要删除的模块
                modulesTag.findSubTags("module").forEach { moduleTag ->
                    if (moduleTag.value.text == moduleName) {
                        // 找到了对应的模块，删除它
                        moduleTag.delete()
                        logger.info("已从 modules pom.xml 中删除模块 '$moduleName'")
                    }
                }
            } catch (e: Exception) {
                logger.error("从 modules pom.xml 中删除模块失败", e)
                throw e
            }
        })
    }
    
    /**
     * 从 ruoyi-admin/pom.xml 中删除依赖
     */
    private fun removeFromAdminPom(moduleName: String) {
        val adminPomFile = findFileInProject("ruoyi-admin/pom.xml")
            ?: throw Exception("ruoyi-admin/pom.xml 文件未找到")
            
        val psiFile = PsiManager.getInstance(project).findFile(adminPomFile) as? XmlFile
            ?: throw Exception("无法解析 ruoyi-admin/pom.xml 文件")
            
        WriteCommandAction.runWriteCommandAction(project, "Remove Dependency from Admin Pom.xml", null, {
            try {
                val rootTag = psiFile.rootTag ?: throw Exception("无法找到 pom.xml 根元素")
                val dependenciesTag = rootTag.findFirstSubTag("dependencies")
                    ?: throw Exception("在 admin pom.xml 中未找到 dependencies 标签")
                
                // 查找要删除的依赖
                dependenciesTag.findSubTags("dependency").forEach { dependencyTag ->
                    val artifactIdTag = dependencyTag.findFirstSubTag("artifactId")
                    if (artifactIdTag?.value?.text == moduleName) {
                        // 找到了对应的依赖，删除它
                        dependencyTag.delete()
                        logger.info("已从 admin pom.xml 中删除模块 '$moduleName' 的依赖")
                    }
                }
            } catch (e: Exception) {
                logger.error("从 admin pom.xml 中删除依赖失败", e)
                throw e
            }
        })
    }
    
    /**
     * 删除模块目录
     */
    private fun deleteModuleDirectory(moduleName: String) {
        // 获取当前配置的模块前缀，并移除结尾的连字符（如果有）
        val prefix = DependencyConfigService.getInstance().modulePrefix.trimEnd('-')
        
        val modulesDir = findFileInProject("$prefix-modules") ?: throw Exception("$prefix-modules 目录未找到")
        val moduleDir = modulesDir.findChild(moduleName) ?: throw Exception("模块 '$moduleName' 目录未找到")
        
        WriteCommandAction.runWriteCommandAction(project, "Delete Module Directory", null, {
            try {
                moduleDir.delete(this)
                logger.info("已删除模块 '$moduleName' 的目录")
            } catch (e: Exception) {
                logger.error("删除模块目录失败", e)
                throw e
            }
        })
    }
    
    /**
     * 安排延迟刷新任务，确保Maven项目导入完成后能够正确显示新模块
     */
    private fun scheduleDelayedRefresh(project: Project, moduleName: String) {
        // 获取当前配置的模块前缀，并移除结尾的连字符（如果有）
        val prefix = DependencyConfigService.getInstance().modulePrefix.trimEnd('-')
        
        // 首次延迟1秒刷新
        AppExecutorUtil.getAppScheduledExecutorService().schedule({
            ApplicationManager.getApplication().invokeLater {
                refreshMavenProject(project)
            }
        }, 1, TimeUnit.SECONDS)
        
        // 再次延迟3秒刷新，确保完成
        AppExecutorUtil.getAppScheduledExecutorService().schedule({
            ApplicationManager.getApplication().invokeLater {
                refreshMavenProject(project)
                
                // 特别关注新生成的模块目录
                val basePath = project.basePath
                if (basePath != null) {
                    val modulePath = "$basePath/$prefix-modules/$moduleName"
                    val moduleDir = LocalFileSystem.getInstance().findFileByPath(modulePath)
                    if (moduleDir != null) {
                        // 使用异步方式刷新以提高性能
                        moduleDir.refresh(true, true)
                        
                        // 尝试再次触发Maven刷新
                        val mavenProjectsManager = MavenProjectsManager.getInstance(project)
                        val pomFile = moduleDir.findChild("pom.xml")
                        if (pomFile != null) {
                            // 确保pom文件已经刷新
                            pomFile.refresh(true, false)
                            
                            if (!mavenProjectsManager.isManagedFile(pomFile)) {
                                // 添加pom文件到Maven管理
                                mavenProjectsManager.addManagedFilesOrUnignore(listOf(pomFile))
                                
                                // 使用最新的API强制更新Maven项目
                                mavenProjectsManager.forceUpdateAllProjectsOrFindAllAvailablePomFiles()
                            }
                        }
                    }
                }
            }
        }, 3, TimeUnit.SECONDS)
    }
    
    /**
     * 刷新Maven项目
     */
    private fun refreshMavenProject(project: Project) {
        try {
            // 刷新整个项目目录，使用异步方式
            val basePath = project.basePath
            if (basePath != null) {
                val projectDir = LocalFileSystem.getInstance().findFileByPath(basePath)
                projectDir?.refresh(true, true)
            }
            
            // 使用最新的API触发Maven刷新
            val mavenProjectsManager = MavenProjectsManager.getInstance(project)
            
            // 先查找所有可用的pom文件并添加到管理
            mavenProjectsManager.forceUpdateAllProjectsOrFindAllAvailablePomFiles()
        } catch (e: Exception) {
            logger.error("刷新Maven项目失败: ${e.message}", e)
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

