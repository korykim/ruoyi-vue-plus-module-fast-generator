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
import java.io.File


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
            
            // 强制刷新当前项目配置信息 - 确保跨项目操作时配置正确
            DependencyConfigService.getInstance().refreshCurrentProjectConfig(project)
            
            // 自动更新依赖配置中的groupId和前缀
            if (groupId != "org.dromara" || !currentPrefix.startsWith("ruoyi-")) {
                try {
                    // 每次创建模块都确保配置使用当前项目的信息
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
        // 获取前缀（优先使用传入的前缀）
        val configPrefix = DependencyConfigService.getInstance().modulePrefix
        var prefix = modulePrefix ?: configPrefix
        
        // 确保前缀以连字符结尾
        if (!prefix.endsWith("-")) {
            prefix += "-"
        }
        
        // 处理用户输入的模块名称
        var name = moduleName.trim()
        
        // 如果用户输入已经包含前缀，避免重复添加
        if (!name.startsWith(prefix)) {
            // 检查用户输入是否包含连字符，如果已经包含则需要考虑是否是另一种形式的前缀
            if (name.contains("-")) {
                // 提取可能的现有前缀
                val possiblePrefix = name.substringBefore("-") + "-"
                
                // 检查现有前缀是否与系统前缀的基础部分相同（忽略大小写）
                // 例如：用户输入"rycloud-module"，而系统前缀是"RyCloud-"
                if (possiblePrefix.equals(prefix, ignoreCase = true)) {
                    // 使用用户输入的名称，但确保前缀部分使用标准格式
                    val moduleSuffix = name.substringAfter("-")
                    name = prefix + moduleSuffix
                } else {
                    // 现有连字符不是前缀的一部分，添加完整前缀
                    name = prefix + name
                }
            } else {
                // 没有连字符，直接添加前缀
                name = prefix + name
            }
        }
        
        logger.info("模块名称标准化: 输入=$moduleName, 标准化后=$name")
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
                    val prefixWithDash = DependencyConfigService.getInstance().modulePrefix.trimEnd('-') + "-"
                    val simpleModuleName = if (moduleName.startsWith(prefixWithDash)) {
                        moduleName.substringAfter(prefixWithDash)
                    } else {
                        // 如果模块名不以前缀开头，直接使用
                        moduleName
                    }
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
                
                // 使用模块名（去掉连字符和前缀）作为包名的最后一部分
                val moduleNameWithoutPrefix = if (moduleName.startsWith("$prefix-")) {
                    moduleName.substringAfter("$prefix-")
                } else {
                    // 如果模块名不以前缀开头，则直接使用模块名
                    moduleName
                }
                // 去掉模块名称中的连字符
                val moduleNameWithoutDash = moduleNameWithoutPrefix.replace("-", "")
                
                logger.info("创建包结构: $groupId.$moduleNameWithoutDash")
                
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
        val prefixWithDash = "$prefix-"
        
        // 提取实际的模块名称（去掉前缀部分）
        val simpleModuleName = if (moduleName.startsWith(prefixWithDash)) {
            moduleName.substringAfter(prefixWithDash)
        } else {
            // 如果模块名不以前缀开头，则直接使用
            moduleName
        }
        
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
            DependencyConfigService.getInstance().getDependenciesContent(dependencyConfigName) ?: getDefaultDependencies(groupId, prefix)
        } else {
            // 如果没有指定配置或者配置不存在，尝试使用第一个配置
            val firstConfigName = DependencyConfigService.getInstance().getConfigNames().firstOrNull()
            if (firstConfigName != null) {
                val content = DependencyConfigService.getInstance().getDependenciesContent(firstConfigName)
                if (content != null) {
                    content
                } else {
                    getDefaultDependencies(groupId, prefix)
                }
            } else {
                getDefaultDependencies(groupId, prefix)
            }
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
            
            // 获取并记录要删除的模块路径
            val prefix = DependencyConfigService.getInstance().modulePrefix.trimEnd('-')
            val modulesDir = findFileInProject("$prefix-modules") ?: throw Exception("$prefix-modules 目录未找到")
            val moduleDir = modulesDir.findChild(moduleName) ?: throw Exception("模块 '$moduleName' 目录未找到")
            val modulePath = moduleDir.path
            
            // 提前刷新项目
            refreshProjectView()
            
            // 1. 从根目录 pom.xml 中删除依赖
            removeFromRootPom(moduleName)
            
            // 2. 从 ruoyi-modules/pom.xml 中删除模块
            removeFromModulesPom(moduleName)
            
            // 3. 从 ruoyi-admin/pom.xml 中删除依赖
            removeFromAdminPom(moduleName)
            
            // 4. 刷新项目，确保POM修改保存
            refreshProjectView()
            
            // 5. 进行预删除检查，确保模块目录已准备好被删除
            preDeleteCheck(modulePath)
            
            // 6. 删除模块目录
            deleteModuleDirectory(moduleName)
            
            // 7. 删除后立即进行强制刷新，清理可能的无效引用
            forceRefreshAfterDelete(modulePath, modulesDir)
            
            // 8. 显式触发Maven项目导入
            importMavenChanges()
            
            // 9. 延迟刷新确保IDE虚拟文件系统状态更新
            scheduleDelayedRefresh(project, moduleName, true)
            
            // 10. 增加额外延迟刷新，彻底清理无效引用
            scheduleAdditionalRefresh()
            
            logger.info("模块 '$moduleName' 删除成功")
            return true
        } catch (e: Exception) {
            logger.error("删除模块失败: ${e.message}", e)
            return false
        }
    }
    
    /**
     * 预删除检查，确保模块目录已准备好被删除
     */
    private fun preDeleteCheck(modulePath: String) {
        try {
            val moduleDir = LocalFileSystem.getInstance().findFileByPath(modulePath)
            if (moduleDir != null) {
                // 强制刷新目录
                moduleDir.refresh(true, true)
                
                // 尝试预先删除可能导致问题的文件（如.iml文件）
                WriteCommandAction.runWriteCommandAction(project, "Pre-Delete Check", null, {
                    try {
                        moduleDir.children.filter { it.name.endsWith(".iml") }.forEach {
                            it.delete(this)
                        }
                    } catch (e: Exception) {
                        logger.warn("预删除检查中清理.iml文件失败，但这不是致命错误: ${e.message}")
                    }
                })
            }
        } catch (e: Exception) {
            logger.warn("预删除检查失败，但这不是致命错误: ${e.message}")
        }
    }
    
    /**
     * 强制刷新删除后的状态
     */
    private fun forceRefreshAfterDelete(deletedPath: String, parentDir: VirtualFile) {
        try {
            // 立即刷新父目录
            parentDir.refresh(true, true)
            
            // 延迟执行额外刷新
            ApplicationManager.getApplication().invokeLater {
                try {
                    // 再次刷新父目录
                    if (parentDir.isValid) {
                        parentDir.refresh(true, true)
                    }
                    
                    // 检查并清理可能存在的无效引用
                    val checkDeleted = try {
                        LocalFileSystem.getInstance().findFileByPath(deletedPath)
                    } catch (e: Exception) {
                        logger.warn("获取已删除路径时出错，路径可能已完全失效: ${e.message}")
                        null
                    }
                    
                    if (checkDeleted != null) {
                        try {
                            if (checkDeleted.isValid && checkDeleted.exists()) {
                                logger.warn("模块目录可能未被完全删除，尝试强制刷新")
                                checkDeleted.refresh(true, true)
                            } else if (checkDeleted.isValid) {
                                // 文件不存在但引用仍然存在，尝试清理
                                logger.warn("模块目录已被删除但引用仍然存在，尝试清理引用")
                                
                                // 使用反射尝试清理VFS缓存
                                try {
                                    val fsClass = Class.forName("com.intellij.openapi.vfs.newvfs.impl.VirtualFileSystemEntry")
                                    val invalidateMethod = fsClass.getDeclaredMethod("invalidate")
                                    invalidateMethod.isAccessible = true
                                    invalidateMethod.invoke(checkDeleted)
                                    logger.info("成功调用invalidate方法清理无效引用")
                                } catch (e: Exception) {
                                    // 静默处理，因为这是尝试性操作
                                    logger.debug("清理无效引用失败，但这不是致命错误: ${e.message}")
                                }
                            }
                        } catch (e: Exception) {
                            logger.warn("处理已删除引用时出错: ${e.message}")
                        }
                    }
                    
                    // 刷新整个项目
                    refreshProjectView()
                    
                    // 强制一次GC尝试清理无效引用
                    System.gc()
                } catch (e: Exception) {
                    logger.warn("强制刷新删除后状态失败，但这不是致命错误: ${e.message}")
                }
            }
        } catch (e: Exception) {
            logger.warn("强制刷新删除后状态失败，但这不是致命错误: ${e.message}")
        }
    }
    
    /**
     * 安排额外的延迟刷新，确保清理所有无效引用
     */
    private fun scheduleAdditionalRefresh() {
        // 延迟5秒进行最终刷新
        AppExecutorUtil.getAppScheduledExecutorService().schedule({
            ApplicationManager.getApplication().invokeLater {
                try {
                    // 刷新整个项目
                    val basePath = project.basePath
                    if (basePath != null) {
                        val projectDir = LocalFileSystem.getInstance().findFileByPath(basePath)
                        projectDir?.refresh(true, true)
                    }
                    
                    // 使用反射尝试调用VFS缓存刷新
                    try {
                        val vfsClass = Class.forName("com.intellij.openapi.vfs.newvfs.persistent.PersistentFS")
                        val getInstance = vfsClass.getDeclaredMethod("getInstance")
                        val instance = getInstance.invoke(null)
                        val syncMethod = vfsClass.getDeclaredMethod("syncRefresh")
                        syncMethod.invoke(instance)
                        logger.info("成功调用PersistentFS.syncRefresh方法进行VFS刷新")
                    } catch (e: Exception) {
                        // 静默处理，因为这是尝试性操作
                    }
                    
                    // 清理无效文件引用
                    InvalidFilesCleaner.clearInvalidFiles(project)
                    
                    // 刷新Maven项目
                    refreshMavenProject(project)
                } catch (e: Exception) {
                    logger.warn("额外延迟刷新失败，但这不是致命错误: ${e.message}")
                }
            }
        }, 5, TimeUnit.SECONDS)
        
        // 延迟10秒再次刷新，确保所有操作完成
        AppExecutorUtil.getAppScheduledExecutorService().schedule({
            ApplicationManager.getApplication().invokeLater {
                try {
                    refreshMavenProject(project)
                    
                    // 再次清理无效文件引用
                    InvalidFilesCleaner.clearInvalidFiles(project)
                    
                    // 强制进行一次GC，帮助释放资源
                    System.gc()
                } catch (e: Exception) {
                    // 静默处理，因为这是最后的尝试性操作
                }
            }
        }, 10, TimeUnit.SECONDS)
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
                // 记录模块路径，用于后续验证
                val modulePath = moduleDir.path
                
                // 先刷新模块目录，确保状态正确
                try {
                    moduleDir.refresh(true, true)
                } catch (e: Exception) {
                    logger.warn("刷新模块目录失败，但继续执行删除: ${e.message}")
                }
                
                // 先尝试删除目录中的所有内容，然后再删除目录本身
                // 这样可以减少因为目录非空导致的删除失败问题
                try {
                    val deleteResult = deleteDirectoryContents(moduleDir)
                    
                    if (deleteResult) {
                        // 如果内容清理成功，尝试删除目录本身
                        try {
                            moduleDir.delete(this)
                            logger.info("已删除模块 '$moduleName' 的目录")
                        } catch (e: Exception) {
                            logger.error("删除模块目录失败: ${e.message}", e)
                            throw e
                        }
                    } else {
                        // 如果内容清理失败，直接尝试删除整个目录
                        try {
                            moduleDir.delete(this)
                            logger.info("已直接删除模块 '$moduleName' 的目录")
                        } catch (e: Exception) {
                            logger.error("直接删除模块目录失败: ${e.message}", e)
                            throw e
                        }
                    }
                } catch (e: Exception) {
                    logger.error("删除模块目录内容失败: ${e.message}", e)
                    
                    // 如果删除模块目录失败，尝试备用删除方法
                    try {
                        logger.info("尝试备用删除方法 - 使用java.io.File")
                        val fileObj = File(modulePath)
                        if (fileObj.exists()) {
                            deleteRecursively(fileObj)
                            logger.info("使用备用方法删除成功")
                        }
                    } catch (e2: Exception) {
                        logger.error("备用删除方法也失败: ${e2.message}", e2)
                        throw e
                    }
                }
                
                // 立即刷新模块所在目录，确保虚拟文件系统状态更新
                try {
                    modulesDir.refresh(true, true)
                } catch (e: Exception) {
                    logger.warn("刷新父目录失败，但不中断流程: ${e.message}")
                }
                
                // 验证目录确实被删除
                try {
                    val checkDeleted = LocalFileSystem.getInstance().findFileByPath(modulePath)
                    if (checkDeleted != null && checkDeleted.exists()) {
                        logger.warn("目录似乎没有被完全删除，可能需要手动操作")
                    }
                } catch (e: Exception) {
                    logger.warn("验证删除状态失败，但这不是关键步骤: ${e.message}")
                }
            } catch (e: Exception) {
                logger.error("删除模块目录时发生异常: ${e.message}", e)
                throw e
            }
        })
    }
    
    /**
     * 使用Java原生方法递归删除文件夹
     * 当IntelliJ的VFS API失败时的备用方法
     */
    private fun deleteRecursively(file: File): Boolean {
        if (file.isDirectory) {
            file.listFiles()?.forEach { child ->
                deleteRecursively(child)
            }
        }
        return file.delete()
    }
    
    /**
     * 递归删除目录内容，但保留目录本身
     * 
     * @param dir 要清空的目录
     * @return 是否成功清空目录内容
     */
    private fun deleteDirectoryContents(dir: VirtualFile): Boolean {
        try {
            // 先处理所有子目录和文件
            for (child in dir.children) {
                if (child.isDirectory) {
                    // 递归删除子目录内容
                    deleteDirectoryContents(child)
                    // 删除空目录
                    child.delete(this)
                } else {
                    // 删除文件
                    child.delete(this)
                }
            }
            return true
        } catch (e: Exception) {
            logger.warn("删除目录内容失败: ${e.message}")
            return false
        }
    }
    
    /**
     * 安排延迟刷新任务，确保Maven项目导入完成后能够正确显示新模块
     * 
     * @param project 项目实例
     * @param moduleName 模块名称 
     * @param isDelete 是否为删除操作，默认为false(创建/更新操作)
     */
    private fun scheduleDelayedRefresh(project: Project, moduleName: String, isDelete: Boolean = false) {
        // 获取当前配置的模块前缀，并移除结尾的连字符（如果有）
        val prefix = DependencyConfigService.getInstance().modulePrefix.trimEnd('-')
        
        // 首次延迟1秒刷新
        AppExecutorUtil.getAppScheduledExecutorService().schedule({
            ApplicationManager.getApplication().invokeLater {
                refreshMavenProject(project)
                
                // 对于删除操作，需要特别清理相关状态
                if (isDelete) {
                    try {
                        val basePath = project.basePath
                        if (basePath != null) {
                            // 清理可能存在的无效引用路径
                            val modulePath = "$basePath/$prefix-modules/$moduleName"
                            
                            try {
                                val checkFile = LocalFileSystem.getInstance().findFileByPath(modulePath)
                                if (checkFile != null) {
                                    // 如果路径已不存在但引用仍然存在，强制刷新
                                    if (checkFile.isValid && !checkFile.exists()) {
                                        logger.warn("发现删除模块的无效引用，尝试清理")
                                        // 使用反射尝试清理VFS缓存
                                        try {
                                            val fsClass = Class.forName("com.intellij.openapi.vfs.newvfs.impl.VirtualFileSystemEntry")
                                            val invalidateMethod = fsClass.getDeclaredMethod("invalidate")
                                            invalidateMethod.isAccessible = true
                                            invalidateMethod.invoke(checkFile)
                                        } catch (e: Exception) {
                                            // 静默处理反射异常
                                            logger.debug("通过反射清理无效引用失败: ${e.message}")
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                logger.warn("检查删除的模块路径时出错: ${e.message}")
                            }
                            
                            // 强制刷新modules目录
                            val modulesPath = "$basePath/$prefix-modules"
                            try {
                                val modulesDir = LocalFileSystem.getInstance().findFileByPath(modulesPath)
                                modulesDir?.refresh(true, true)
                            } catch (e: Exception) {
                                logger.warn("刷新modules目录时出错: ${e.message}")
                            }
                        }
                    } catch (e: Exception) {
                        logger.warn("删除操作中额外刷新失败，但不中断流程: ${e.message}")
                    }
                }
            }
        }, 1, TimeUnit.SECONDS)
        
        // 再次延迟3秒刷新，确保完成
        AppExecutorUtil.getAppScheduledExecutorService().schedule({
            ApplicationManager.getApplication().invokeLater {
                refreshMavenProject(project)
                
                // 只有在创建/更新模式下才处理特定模块目录
                if (!isDelete) {
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
                } else {
                    // 在删除模式下，强制清理无效的Maven引用
                    try {
                        // 主动触发Maven重新扫描
                        val mavenProjectsManager = MavenProjectsManager.getInstance(project)
                        mavenProjectsManager.forceUpdateAllProjectsOrFindAllAvailablePomFiles()
                        
                        // 主动触发项目重载
                        val basePath = project.basePath
                        if (basePath != null) {
                            try {
                                val projectDir = LocalFileSystem.getInstance().findFileByPath(basePath)
                                projectDir?.refresh(true, true)
                            } catch (e: Exception) {
                                logger.warn("刷新项目根目录时出错: ${e.message}")
                            }
                        }
                        
                        // 主动尝试清理VFS缓存
                        InvalidFilesCleaner.clearInvalidFiles(project)
                    } catch (e: Exception) {
                        logger.warn("删除模式下Maven刷新失败，但不中断流程: ${e.message}")
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
                
                // 获取当前配置的模块前缀
                val prefix = DependencyConfigService.getInstance().modulePrefix.trimEnd('-')
                
                // 特别刷新modules目录，因为它是模块变更的主要位置
                val modulesPath = "$basePath/$prefix-modules"
                val modulesDir = LocalFileSystem.getInstance().findFileByPath(modulesPath)
                modulesDir?.refresh(true, true)
                
                // 尝试刷新其他重要目录
                val adminDirName = findAdminDirName()
                val adminPath = "$basePath/$adminDirName"
                val adminDir = LocalFileSystem.getInstance().findFileByPath(adminPath)
                adminDir?.refresh(true, true)
            }
            
            // 使用最新的API触发Maven刷新
            val mavenProjectsManager = MavenProjectsManager.getInstance(project)
            
            // 先查找所有可用的pom文件并添加到管理
            mavenProjectsManager.forceUpdateAllProjectsOrFindAllAvailablePomFiles()
            
            // 尝试调用其他可能有用的刷新方法
            try {
                // 请求项目同步
                invokeProjectSync(project)
            } catch (e: Exception) {
                logger.warn("尝试执行项目同步时出错，但不影响主流程: ${e.message}")
            }
        } catch (e: Exception) {
            logger.error("刷新Maven项目失败: ${e.message}", e)
            // 忽略异常，不影响主流程
        }
    }
    
    /**
     * 尝试调用项目同步方法
     * 这是一个尝试性方法，用于通过反射调用一些可能有助于刷新项目的API
     */
    private fun invokeProjectSync(project: Project) {
        try {
            // 尝试调用MavenProjectsManager的同步方法
            val mavenProjectsManager = MavenProjectsManager.getInstance(project)
            
            // 使用反射尝试调用 scheduleForceReimport 方法，该方法在某些版本可用
            try {
                val method = mavenProjectsManager.javaClass.getMethod("scheduleForceReimport")
                method.invoke(mavenProjectsManager)
                logger.info("成功调用scheduleForceReimport方法")
            } catch (e: Exception) {
                // 静默处理，因为这是可选尝试
            }
            
            // 使用反射尝试调用 scheduleUpdateAll 方法，该方法在某些版本可用
            try {
                val method = mavenProjectsManager.javaClass.getMethod("scheduleUpdateAll")
                method.invoke(mavenProjectsManager)
                logger.info("成功调用scheduleUpdateAll方法")
            } catch (e: Exception) {
                // 静默处理，因为这是可选尝试
            }
        } catch (e: Exception) {
            // 静默处理所有异常，因为这只是额外的尝试性操作
        }
    }
    
    companion object {
        /**
         * 无效文件清理器，用于清理IDE中的无效文件引用
         */
        private object InvalidFilesCleaner {
            private val logger = logger<ModuleGeneratorService>()
            
            /**
             * 清理项目中的无效文件引用
             */
            fun clearInvalidFiles(project: Project) {
                try {
                    // 1. 尝试通过反射访问并调用VirtualFileManager的cleanupForNextTest方法
                    tryCleanupVirtualFileManager()
                    
                    // 2. 尝试通过反射访问并清理FileManagerImpl中的myVirtualFilePointers缓存
                    tryCleanupFileManagerImpl(project)
                    
                    // 3. 尝试清理LocalFileSystem中的myRootsCache
                    tryCleanupLocalFileSystem()
                    
                    // 4. 强制刷新整个项目
                    val basePath = project.basePath
                    if (basePath != null) {
                        val projectDir = LocalFileSystem.getInstance().findFileByPath(basePath)
                        projectDir?.refresh(true, true)
                    }
                } catch (e: Exception) {
                    logger.warn("清理无效文件引用时出错，但这不影响主要功能: ${e.message}")
                }
            }
            
            /**
             * 尝试清理VirtualFileManager
             */
            private fun tryCleanupVirtualFileManager() {
                try {
                    val vfmClass = Class.forName("com.intellij.openapi.vfs.impl.VirtualFileManagerImpl")
                    val getInstance = Class.forName("com.intellij.openapi.vfs.VirtualFileManager").getMethod("getInstance")
                    val vfm = getInstance.invoke(null)
                    
                    // 尝试调用cleanupForNextTest方法
                    val cleanupMethod = vfmClass.getDeclaredMethod("cleanupForNextTest")
                    cleanupMethod.isAccessible = true
                    cleanupMethod.invoke(vfm)
                    logger.info("成功调用VirtualFileManagerImpl.cleanupForNextTest方法")
                } catch (e: Exception) {
                    // 静默处理，因为这是尝试性操作
                }
            }
            
            /**
             * 尝试清理FileManagerImpl
             */
            private fun tryCleanupFileManagerImpl(project: Project) {
                try {
                    // 获取PsiManager实例
                    val psiManager = PsiManager.getInstance(project)
                    
                    // 获取FileManagerImpl实例
                    val fileManagerField = psiManager.javaClass.getDeclaredField("myFileManager")
                    fileManagerField.isAccessible = true
                    val fileManager = fileManagerField.get(psiManager)
                    
                    // 清理myVirtualFilePointers缓存
                    val pointersMapField = fileManager.javaClass.getDeclaredField("myVirtualFilePointers")
                    pointersMapField.isAccessible = true
                    val pointersMap = pointersMapField.get(fileManager)
                    
                    // 如果是Map类型，尝试清理
                    if (pointersMap is MutableMap<*, *>) {
                        val mapSize = pointersMap.size
                        pointersMap.clear()
                        logger.info("已清理FileManagerImpl.myVirtualFilePointers缓存，原大小: $mapSize")
                    }
                } catch (e: Exception) {
                    // 静默处理，因为这是尝试性操作
                }
            }
            
            /**
             * 尝试清理LocalFileSystem
             */
            private fun tryCleanupLocalFileSystem() {
                try {
                    val localFileSystem = LocalFileSystem.getInstance()
                    
                    // 尝试访问并清理myRootsCache字段
                    val rootsCacheField = localFileSystem.javaClass.getDeclaredField("myRootsCache")
                    rootsCacheField.isAccessible = true
                    val rootsCache = rootsCacheField.get(localFileSystem)
                    
                    // 如果是Map类型，尝试清理
                    if (rootsCache is MutableMap<*, *>) {
                        val cacheSize = rootsCache.size
                        rootsCache.clear()
                        logger.info("已清理LocalFileSystem.myRootsCache，原大小: $cacheSize")
                    }
                } catch (e: Exception) {
                    // 静默处理，因为这是尝试性操作
                }
            }
        }
        
        /**
         * 默认依赖内容，当没有配置或配置失效时使用
         * 使用当前项目检测到的groupId和prefix，确保跨项目使用时依赖配置正确
         */
        fun getDefaultDependencies(groupId: String = "org.dromara", prefix: String = "ruoyi-"): String {
            // 从DependencyConfigService获取最新的项目groupId和prefix
            // 这样即使传入的参数是默认值，也会使用当前项目的实际值
            val configService = DependencyConfigService.getInstance()
            val projectInfo = configService.getDetectedProjectInfo()
            
            // 如果能获取到当前项目信息，则优先使用，否则使用传入的参数
            val actualGroupId = projectInfo?.first ?: groupId
            val actualPrefix = projectInfo?.second ?: prefix
            
            return """
 
            <!-- 通用工具-->
            <dependency>
                <groupId>$actualGroupId</groupId>
                <artifactId>${actualPrefix}common-core</artifactId>
            </dependency>

            <dependency>
                <groupId>$actualGroupId</groupId>
                <artifactId>${actualPrefix}common-doc</artifactId>
            </dependency>

            <dependency>
                <groupId>$actualGroupId</groupId>
                <artifactId>${actualPrefix}common-sms</artifactId>
            </dependency>

            <dependency>
                <groupId>$actualGroupId</groupId>
                <artifactId>${actualPrefix}common-mail</artifactId>
            </dependency>

            <dependency>
                <groupId>$actualGroupId</groupId>
                <artifactId>${actualPrefix}common-redis</artifactId>
            </dependency>

            <dependency>
                <groupId>$actualGroupId</groupId>
                <artifactId>${actualPrefix}common-idempotent</artifactId>
            </dependency>

            <dependency>
                <groupId>$actualGroupId</groupId>
                <artifactId>${actualPrefix}common-mybatis</artifactId>
            </dependency>

            <dependency>
                <groupId>$actualGroupId</groupId>
                <artifactId>${actualPrefix}common-log</artifactId>
            </dependency>

            <dependency>
                <groupId>$actualGroupId</groupId>
                <artifactId>${actualPrefix}common-excel</artifactId>
            </dependency>

            <dependency>
                <groupId>$actualGroupId</groupId>
                <artifactId>${actualPrefix}common-security</artifactId>
            </dependency>

            <dependency>
                <groupId>$actualGroupId</groupId>
                <artifactId>${actualPrefix}common-web</artifactId>
            </dependency>

            <dependency>
                <groupId>$actualGroupId</groupId>
                <artifactId>${actualPrefix}common-ratelimiter</artifactId>
            </dependency>

            <dependency>
                <groupId>$actualGroupId</groupId>
                <artifactId>${actualPrefix}common-translation</artifactId>
            </dependency>

            <dependency>
                <groupId>$actualGroupId</groupId>
                <artifactId>${actualPrefix}common-sensitive</artifactId>
            </dependency>

            <dependency>
                <groupId>$actualGroupId</groupId>
                <artifactId>${actualPrefix}common-encrypt</artifactId>
            </dependency>

            <dependency>
                <groupId>$actualGroupId</groupId>
                <artifactId>${actualPrefix}common-tenant</artifactId>
            </dependency>

            <dependency>
                <groupId>$actualGroupId</groupId>
                <artifactId>${actualPrefix}common-websocket</artifactId>
            </dependency>
 
            """
        }
    }
}

