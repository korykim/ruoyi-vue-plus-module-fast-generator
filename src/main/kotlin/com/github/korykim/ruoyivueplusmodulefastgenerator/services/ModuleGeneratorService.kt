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
     * 验证项目结构是否符合RuoYi-Vue-Plus项目结构
     * 
     * @return 包含验证结果和描述信息的Pair
     */
    private fun validateProjectStructure(): Pair<Boolean, String> {
        val basePath = project.basePath ?: return Pair(false, "无法获取项目路径")
        
        // 获取当前配置的模块前缀
        val prefix = DependencyConfigService.getInstance().modulePrefix.trimEnd('-')
        
        // 检查pom.xml是否存在
        val rootPomExists = findFileInProject("pom.xml") != null
        if (!rootPomExists) {
            return Pair(false, "未找到根目录pom.xml文件，当前项目可能不是Maven项目")
        }
        
        // 检查modules目录是否存在
        val modulesDir = findFileInProject("$prefix-modules")
        if (modulesDir == null || !modulesDir.isDirectory) {
            // 尝试寻找可能的modules目录
            val possibleModulesDirs = findPossibleModulesDirs()
            if (possibleModulesDirs.isNotEmpty()) {
                logger.info("未找到$prefix-modules目录，但发现了可能的替代目录: ${possibleModulesDirs.joinToString()}")
                // 如果找到了可能的modules目录，则更新前缀配置
                val firstDir = possibleModulesDirs.first()
                if (firstDir.endsWith("-modules")) {
                    val newPrefix = firstDir.substringBefore("-modules") + "-"
                    DependencyConfigService.getInstance().modulePrefix = newPrefix
                    logger.info("自动更新模块前缀为: $newPrefix")
                    return validateProjectStructure() // 重新验证
                }
            }
            return Pair(false, "未找到$prefix-modules目录，当前项目可能不是RuoYi-Vue-Plus项目")
        }
        
        // 检查modules/pom.xml是否存在
        val modulesPomExists = findFileInProject("$prefix-modules/pom.xml") != null
        if (!modulesPomExists) {
            return Pair(false, "未找到$prefix-modules/pom.xml文件，模块管理可能不完整")
        }
        
        // 检查admin目录是否存在 - 可能是prefix-admin或ruoyi-admin
        val adminExists = findFileInProject("$prefix-admin") != null || findFileInProject("ruoyi-admin") != null
        
        // 如果找不到admin目录，尝试查找其他可能的admin目录
        if (!adminExists) {
            val possibleAdminDirs = findPossibleAdminDirs()
            if (possibleAdminDirs.isNotEmpty()) {
                logger.info("未找到$prefix-admin目录，但发现了可能的替代目录: ${possibleAdminDirs.joinToString()}")
                return Pair(true, "找到可能的admin目录: ${possibleAdminDirs.first()}")
            }
            return Pair(false, "未找到$prefix-admin或ruoyi-admin目录，当前项目可能不是标准的RuoYi-Vue-Plus项目")
        }
        
        return Pair(true, "项目结构验证通过")
    }
    
    /**
     * 查找可能的modules目录
     */
    private fun findPossibleModulesDirs(): List<String> {
        val basePath = project.basePath ?: return emptyList()
        
        val baseDir = LocalFileSystem.getInstance().findFileByPath(basePath) ?: return emptyList()
        val possibleDirs = mutableListOf<String>()
        
        // 查找项目根目录下所有以-modules结尾的目录
        baseDir.children.forEach { child ->
            if (child.isDirectory && child.name.endsWith("-modules")) {
                possibleDirs.add(child.name)
            }
        }
        
        return possibleDirs
    }
    
    /**
     * 查找可能的admin目录
     */
    private fun findPossibleAdminDirs(): List<String> {
        val basePath = project.basePath ?: return emptyList()
        
        val baseDir = LocalFileSystem.getInstance().findFileByPath(basePath) ?: return emptyList()
        val possibleDirs = mutableListOf<String>()
        
        // 查找项目根目录下所有以-admin结尾的目录
        baseDir.children.forEach { child ->
            if (child.isDirectory && child.name.endsWith("-admin")) {
                possibleDirs.add(child.name)
            }
        }
        
        return possibleDirs
    }
    
    /**
     * 生成新模块
     *
     * @param moduleName 模块名称
     * @param dependencyConfigName 依赖配置名称，如果为null则使用默认依赖
     * @param modulePrefix 模块前缀，如果为null则使用配置服务中的默认前缀
     * @return 操作是否成功
     */
    fun generateModule(moduleName: String, dependencyConfigName: String? = null, modulePrefix: String? = null): Boolean {
        try {
            if (moduleName.isBlank()) {
                throw IllegalArgumentException("模块名称不能为空")
            }
            
            // 验证项目结构
            val (isValid, message) = validateProjectStructure()
            if (!isValid) {
                logger.warn("项目结构验证失败: $message")
                // 继续执行，不中断流程，但记录警告
            }
            
            // 获取项目信息，并更新依赖配置
            val groupId = findProjectGroupId()
            val currentPrefix = DependencyConfigService.getInstance().modulePrefix
            
            // 自动更新依赖配置中的groupId和前缀
            if (groupId != "org.dromara" || !currentPrefix.startsWith("ruoyi-")) {
                try {
                    DependencyConfigService.getInstance().updateDependenciesInfo(groupId, currentPrefix)
                    logger.info("已更新依赖配置信息, groupId: $groupId, 前缀: $currentPrefix")
                } catch (e: Exception) {
                    logger.error("更新依赖配置信息失败: ${e.message}", e)
                    // 继续执行，不中断流程
                }
            }
            
            // 标准化模块名称（确保格式为ruoyi-xxx）
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
            try {
                updateRootPom(normalizedModuleName)
            } catch (e: Exception) {
                logger.error("更新根目录pom.xml失败: ${e.message}", e)
                // 继续执行，不中断流程
            }
            
            // 2. 修改 ruoyi-modules/pom.xml
            try {
                updateModulesPom(normalizedModuleName)
            } catch (e: Exception) {
                logger.error("更新modules/pom.xml失败: ${e.message}", e)
                // 继续执行，不中断流程
            }
            
            // 3. 修改 ruoyi-admin/pom.xml
            try {
                updateAdminPom(normalizedModuleName)
            } catch (e: Exception) {
                logger.error("更新admin/pom.xml失败: ${e.message}", e)
                // 继续执行，不中断流程
            }
            
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
     * 查找项目组ID
     */
    private fun findProjectGroupId(): String {
        val rootPomFile = findFileInProject("pom.xml") ?: return "org.dromara" // 默认值
        
        val psiFile = PsiManager.getInstance(project).findFile(rootPomFile) as? XmlFile
            ?: return "org.dromara" // 无法解析时使用默认值
            
        val rootTag = psiFile.rootTag ?: return "org.dromara" // 无法找到根元素时使用默认值
        
        // 直接读取<groupId>标签
        val groupIdTag = rootTag.findFirstSubTag("groupId")
        if (groupIdTag != null) {
            return groupIdTag.value.text
        }
        
        // 如果没有直接的groupId标签，尝试读取<parent>下的<groupId>标签
        val parentTag = rootTag.findFirstSubTag("parent")
        if (parentTag != null) {
            val parentGroupIdTag = parentTag.findFirstSubTag("groupId")
            if (parentGroupIdTag != null) {
                return parentGroupIdTag.value.text
            }
        }
        
        // 如果都没找到，返回一个默认值
        return "org.dromara"
    }
    
    /**
     * 更新根目录 pom.xml 文件
     */
    private fun updateRootPom(moduleName: String) {
        val rootPomFile = findFileInProject("pom.xml")
        if (rootPomFile == null) {
            logger.warn("根目录 pom.xml 文件未找到，跳过更新根依赖")
            return // 文件不存在时直接返回，不抛出异常
        }
        
        val psiFile = PsiManager.getInstance(project).findFile(rootPomFile) as? XmlFile
            ?: throw Exception("无法解析 pom.xml 文件")
            
        // 获取项目groupId
        val groupId = findProjectGroupId()
        
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
                            <groupId>$groupId</groupId>
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
        if (modulesPomFile == null) {
            logger.warn("$prefix-modules/pom.xml 文件未找到，跳过更新modules依赖")
            return // 文件不存在时直接返回，不抛出异常
        }
        
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
     * 查找Admin目录名称
     */
    private fun findAdminDirName(): String {
        val prefix = DependencyConfigService.getInstance().modulePrefix.trimEnd('-')
        
        // 优先查找与前缀匹配的admin目录
        val prefixAdminDir = findFileInProject("$prefix-admin")
        if (prefixAdminDir != null && prefixAdminDir.isDirectory) {
            return "$prefix-admin"
        }
        
        // 其次查找ruoyi-admin目录
        val ruoyiAdminDir = findFileInProject("ruoyi-admin")
        if (ruoyiAdminDir != null && ruoyiAdminDir.isDirectory) {
            return "ruoyi-admin"
        }
        
        // 最后查找任何以-admin结尾的目录
        val possibleAdminDirs = findPossibleAdminDirs()
        if (possibleAdminDirs.isNotEmpty()) {
            return possibleAdminDirs.first()
        }
        
        // 如果都找不到，返回默认值
        return "$prefix-admin"
    }
    
    /**
     * 更新 ruoyi-admin/pom.xml 文件
     */
    private fun updateAdminPom(moduleName: String) {
        // 尝试查找正确的admin目录
        val adminDirName = findAdminDirName()
        
        val adminPomFile = findFileInProject("$adminDirName/pom.xml")
        if (adminPomFile == null) {
            logger.warn("$adminDirName/pom.xml 文件未找到，跳过更新admin依赖")
            return // 文件不存在时直接返回，不抛出异常
        }
        
        val psiFile = PsiManager.getInstance(project).findFile(adminPomFile) as? XmlFile
            ?: throw Exception("无法解析 $adminDirName/pom.xml 文件")
        
        // 获取项目groupId
        val groupId = findProjectGroupId()
        
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
                    val simpleModuleName = moduleName.substringAfter(DependencyConfigService.getInstance().modulePrefix.trimEnd('-') + "-")
                    val dependencyXml = """
                        <!-- ${simpleModuleName}模块  -->
                        <dependency>
                            <groupId>$groupId</groupId>
                            <artifactId>$moduleName</artifactId>
                            <version>$version</version>
                        </dependency>
                    """.trimIndent()
                    
                    val factory = XmlElementFactory.getInstance(project)
                    val newDependencyTag = factory.createTagFromText(dependencyXml, XMLLanguage.INSTANCE)
                    dependenciesTag.addSubTag(newDependencyTag, false)
                    
                    logger.info("已添加模块 '$moduleName' 的依赖到 $adminDirName/pom.xml")
                } else {
                    logger.info("模块 '$moduleName' 的依赖已存在于 $adminDirName/pom.xml 中，无需重复添加")
                }
            } catch (e: Exception) {
                logger.error("更新 $adminDirName/pom.xml 失败", e)
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
                
                // 获取项目的groupId，用于创建包结构
                val groupId = findProjectGroupId()
                val packageParts = groupId.split(".")
                
                // 从java目录开始创建包结构
                var currentDir = javaDir
                for (part in packageParts) {
                    val existingPartDir = currentDir.findChild(part)
                    currentDir = if (existingPartDir != null && existingPartDir.isDirectory) {
                        existingPartDir
                    } else {
                        currentDir.createChildDirectory(this, part)
                    }
                }
                
                // 使用模块名（去掉连字符）作为包名的最后一部分
                val moduleNameWithoutDash = moduleName.replace("-", "")
                val existingModuleNameDir = currentDir.findChild(moduleNameWithoutDash)
                val moduleNameDir = if (existingModuleNameDir != null && existingModuleNameDir.isDirectory) {
                    existingModuleNameDir
                } else {
                    currentDir.createChildDirectory(this, moduleNameWithoutDash)
                }
                
                // 创建基本包结构
                createStandardPackages(moduleNameDir)
                
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
        // 获取项目groupId来确定包路径结构
        val groupId = findProjectGroupId()
        val packageParts = groupId.split(".")
        
        WriteCommandAction.runWriteCommandAction(project, "Create Basic Packages", null, {
            try {
                // 当groupId不是org.dromara时，创建正确的包结构
                if (groupId != "org.dromara") {
                    var currentDir = baseDir
                    for (i in 0 until (packageParts.size - 2)) { // 排除已经创建的org.dromara
                        val partName = packageParts[i + 2] // 跳过前两个部分，因为org.dromara已经创建
                        val existingDir = currentDir.findChild(partName)
                        currentDir = if (existingDir != null && existingDir.isDirectory) {
                            existingDir
                        } else {
                            currentDir.createChildDirectory(this, partName)
                        }
                    }
                    
                    // 设置基础包结构的父目录为最终的包目录
                    createStandardPackages(currentDir)
                } else {
                    // 如果是默认的org.dromara，使用原来的结构
                    createStandardPackages(baseDir)
                }
            } catch (e: Exception) {
                logger.error("创建基本包结构失败", e)
                throw e
            }
        })
    }
    
    /**
     * 创建标准的包结构
     */
    private fun createStandardPackages(baseDir: VirtualFile) {
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
            logger.error("创建标准包结构失败", e)
            throw e
        }
    }
    
    /**
     * 生成模块 pom.xml 内容
     * 
     * @param moduleName 模块名称
     * @param dependencyConfigName 依赖配置名称，如果为null则使用默认依赖
     * @return 生成的pom.xml内容
     */
    private fun generateModulePomContent(moduleName: String, dependencyConfigName: String? = null): String {
        val prefix = DependencyConfigService.getInstance().modulePrefix.trimEnd('-')
        val simpleModuleName = moduleName.substringAfter("$prefix-")
        
        // 获取项目版本号
        val version = try {
            findProjectVersion()
        } catch (e: Exception) {
            "${"\$"}{revision}" // 使用默认的表达式如果找不到版本号
        }
        
        // 获取项目groupId
        val groupId = findProjectGroupId()
        
        // 获取依赖配置内容
        val dependenciesContent = if (dependencyConfigName != null) {
            DependencyConfigService.getInstance().getDependenciesContent(dependencyConfigName)
        } else {
            // 如果没有指定配置或者配置不存在，使用默认配置
            DependencyConfigService.getInstance().getConfigNames().firstOrNull()?.let {
                DependencyConfigService.getInstance().getDependenciesContent(it)
            } ?: getDefaultDependencies(groupId, prefix)
        }
        
        return """
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <parent>
        <artifactId>${prefix}-modules</artifactId>
        <groupId>$groupId</groupId>
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
        val rootPomFile = findFileInProject("pom.xml")
        if (rootPomFile == null) {
            logger.warn("根目录 pom.xml 文件未找到，跳过删除根依赖")
            return // 文件不存在时直接返回，不抛出异常
        }
        
        val psiFile = PsiManager.getInstance(project).findFile(rootPomFile) as? XmlFile
            ?: throw Exception("无法解析 pom.xml 文件")
            
        // 获取项目groupId
        val groupId = findProjectGroupId()
        
        WriteCommandAction.runWriteCommandAction(project, "Remove Dependency from Root Pom.xml", null, {
            try {
                // 查找 <dependencyManagement> 元素
                val rootTag = psiFile.rootTag ?: throw Exception("无法找到 pom.xml 根元素")
                val dependencyManagementTag = rootTag.findFirstSubTag("dependencyManagement")
                    ?: throw Exception("在根 pom.xml 中未找到 dependencyManagement 标签")
                    
                val dependenciesTag = dependencyManagementTag.findFirstSubTag("dependencies")
                    ?: throw Exception("在 dependencyManagement 中未找到 dependencies 标签")
                
                // 查找要删除的依赖，匹配groupId和artifactId
                dependenciesTag.findSubTags("dependency").forEach { dependencyTag ->
                    val artifactIdTag = dependencyTag.findFirstSubTag("artifactId")
                    val groupIdTag = dependencyTag.findFirstSubTag("groupId")
                    
                    if (artifactIdTag?.value?.text == moduleName && 
                        (groupIdTag == null || groupIdTag.value?.text == groupId)) {
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
        if (modulesPomFile == null) {
            logger.warn("$prefix-modules/pom.xml 文件未找到，跳过删除modules模块")
            return // 文件不存在时直接返回，不抛出异常
        }
        
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
        // 尝试查找正确的admin目录
        val adminDirName = findAdminDirName()
        
        val adminPomFile = findFileInProject("$adminDirName/pom.xml")
        if (adminPomFile == null) {
            logger.warn("$adminDirName/pom.xml 文件未找到，跳过删除admin依赖")
            return // 文件不存在时直接返回，不抛出异常
        }
        
        val psiFile = PsiManager.getInstance(project).findFile(adminPomFile) as? XmlFile
            ?: throw Exception("无法解析 $adminDirName/pom.xml 文件")
        
        // 获取项目groupId
        val groupId = findProjectGroupId()
        
        WriteCommandAction.runWriteCommandAction(project, "Remove Dependency from Admin Pom.xml", null, {
            try {
                val rootTag = psiFile.rootTag ?: throw Exception("无法找到 pom.xml 根元素")
                val dependenciesTag = rootTag.findFirstSubTag("dependencies")
                    ?: throw Exception("在 admin pom.xml 中未找到 dependencies 标签")
                
                // 查找要删除的依赖，匹配groupId和artifactId
                dependenciesTag.findSubTags("dependency").forEach { dependencyTag ->
                    val artifactIdTag = dependencyTag.findFirstSubTag("artifactId")
                    val groupIdTag = dependencyTag.findFirstSubTag("groupId")
                    
                    if (artifactIdTag?.value?.text == moduleName && 
                        (groupIdTag == null || groupIdTag.value?.text == groupId)) {
                        // 找到了对应的依赖，删除它
                        dependencyTag.delete()
                        logger.info("已从 $adminDirName/pom.xml 中删除模块 '$moduleName' 的依赖")
                    }
                }
            } catch (e: Exception) {
                logger.error("从 $adminDirName/pom.xml 中删除依赖失败", e)
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
        fun getDefaultDependencies(groupId: String = "org.dromara", prefix: String = "ruoyi-"): String {
            return """
 
            <!-- 通用工具-->
            <dependency>
                <groupId>$groupId</groupId>
                <artifactId>${prefix}common-core</artifactId>
            </dependency>

            <dependency>
                <groupId>$groupId</groupId>
                <artifactId>${prefix}common-doc</artifactId>
            </dependency>

            <dependency>
                <groupId>$groupId</groupId>
                <artifactId>${prefix}common-sms</artifactId>
            </dependency>

            <dependency>
                <groupId>$groupId</groupId>
                <artifactId>${prefix}common-mail</artifactId>
            </dependency>

            <dependency>
                <groupId>$groupId</groupId>
                <artifactId>${prefix}common-redis</artifactId>
            </dependency>

            <dependency>
                <groupId>$groupId</groupId>
                <artifactId>${prefix}common-idempotent</artifactId>
            </dependency>

            <dependency>
                <groupId>$groupId</groupId>
                <artifactId>${prefix}common-mybatis</artifactId>
            </dependency>

            <dependency>
                <groupId>$groupId</groupId>
                <artifactId>${prefix}common-log</artifactId>
            </dependency>

            <dependency>
                <groupId>$groupId</groupId>
                <artifactId>${prefix}common-excel</artifactId>
            </dependency>

            <dependency>
                <groupId>$groupId</groupId>
                <artifactId>${prefix}common-security</artifactId>
            </dependency>

            <dependency>
                <groupId>$groupId</groupId>
                <artifactId>${prefix}common-web</artifactId>
            </dependency>

            <dependency>
                <groupId>$groupId</groupId>
                <artifactId>${prefix}common-ratelimiter</artifactId>
            </dependency>

            <dependency>
                <groupId>$groupId</groupId>
                <artifactId>${prefix}common-translation</artifactId>
            </dependency>

            <dependency>
                <groupId>$groupId</groupId>
                <artifactId>${prefix}common-sensitive</artifactId>
            </dependency>

            <dependency>
                <groupId>$groupId</groupId>
                <artifactId>${prefix}common-encrypt</artifactId>
            </dependency>

            <dependency>
                <groupId>$groupId</groupId>
                <artifactId>${prefix}common-tenant</artifactId>
            </dependency>

            <dependency>
                <groupId>$groupId</groupId>
                <artifactId>${prefix}common-websocket</artifactId>
            </dependency>
 
            """
        }
    }
}

