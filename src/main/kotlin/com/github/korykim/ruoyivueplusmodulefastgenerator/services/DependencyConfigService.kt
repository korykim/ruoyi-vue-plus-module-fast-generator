package com.github.korykim.ruoyivueplusmodulefastgenerator.services

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.*
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiManager
import com.intellij.psi.xml.XmlFile
import com.intellij.util.xmlb.XmlSerializerUtil
import com.github.korykim.ruoyivueplusmodulefastgenerator.MyBundle

/**
 * 依赖配置项
 */
data class DependencyConfig(
    var name: String = "",
    var dependencies: String = "",
    var order: Int = 0, // 添加排序字段
) {
    // 无参构造函数，用于序列化
    constructor() : this("", "", 0)
}

/**
 * 依赖配置服务
 * 管理和持久化模块依赖配置
 */
@Service(Service.Level.APP)
@State(
    name = "com.github.korykim.ruoyivueplusmodulefastgenerator.services.DependencyConfigService",
    storages = [Storage("ModuleDependencyConfigs.xml")]
)
class DependencyConfigService : PersistentStateComponent<DependencyConfigService> {
    // 依赖配置列表 - 使用公共变量以支持序列化
    private var dependencyConfigs: MutableList<DependencyConfig> = mutableListOf()
    
    // 模块前缀配置，默认为 "ruoyi-"
    var modulePrefix: String = "ruoyi-"

    init {
        // 初始化默认配置如果是空的
        if (dependencyConfigs.isEmpty()) {
            addDefaultConfigs()
        }
    }

    override fun getState(): DependencyConfigService = this

    override fun loadState(state: DependencyConfigService) {
        XmlSerializerUtil.copyBean(state, this)
        if (dependencyConfigs.isEmpty()) {
            addDefaultConfigs()
        }
    }

    /**
     * 添加默认配置
     */
    private fun addDefaultConfigs() {
        // 检测项目中的groupId和artifactId前缀
        val (detectedGroupId, detectedPrefix) = detectProjectInfo()
        
        // 基础依赖配置
        val basicConfig = DependencyConfig(
            MyBundle.message("dependency.config.basic"),
            """
        <!-- 基础依赖 -->
        <dependency>
            <groupId>$detectedGroupId</groupId>
            <artifactId>${detectedPrefix}common-core</artifactId>
        </dependency>
        
        <dependency>
            <groupId>$detectedGroupId</groupId>
            <artifactId>${detectedPrefix}common-web</artifactId>
        </dependency>
            """.trimIndent(),
            0 // 基础配置排在第一位
        )

        // 完整依赖配置
        val fullConfig = DependencyConfig(
            MyBundle.message("dependency.config.full"),
            """
        <!-- 通用工具-->
        <dependency>
            <groupId>$detectedGroupId</groupId>
            <artifactId>${detectedPrefix}common-core</artifactId>
        </dependency>

        <dependency>
            <groupId>$detectedGroupId</groupId>
            <artifactId>${detectedPrefix}common-doc</artifactId>
        </dependency>

        <dependency>
            <groupId>$detectedGroupId</groupId>
            <artifactId>${detectedPrefix}common-sms</artifactId>
        </dependency>

        <dependency>
            <groupId>$detectedGroupId</groupId>
            <artifactId>${detectedPrefix}common-mail</artifactId>
        </dependency>

        <dependency>
            <groupId>$detectedGroupId</groupId>
            <artifactId>${detectedPrefix}common-redis</artifactId>
        </dependency>

        <dependency>
            <groupId>$detectedGroupId</groupId>
            <artifactId>${detectedPrefix}common-idempotent</artifactId>
        </dependency>

        <dependency>
            <groupId>$detectedGroupId</groupId>
            <artifactId>${detectedPrefix}common-mybatis</artifactId>
        </dependency>

        <dependency>
            <groupId>$detectedGroupId</groupId>
            <artifactId>${detectedPrefix}common-log</artifactId>
        </dependency>

        <dependency>
            <groupId>$detectedGroupId</groupId>
            <artifactId>${detectedPrefix}common-excel</artifactId>
        </dependency>

        <dependency>
            <groupId>$detectedGroupId</groupId>
            <artifactId>${detectedPrefix}common-security</artifactId>
        </dependency>

        <dependency>
            <groupId>$detectedGroupId</groupId>
            <artifactId>${detectedPrefix}common-web</artifactId>
        </dependency>

        <dependency>
            <groupId>$detectedGroupId</groupId>
            <artifactId>${detectedPrefix}common-ratelimiter</artifactId>
        </dependency>

        <dependency>
            <groupId>$detectedGroupId</groupId>
            <artifactId>${detectedPrefix}common-translation</artifactId>
        </dependency>

        <dependency>
            <groupId>$detectedGroupId</groupId>
            <artifactId>${detectedPrefix}common-sensitive</artifactId>
        </dependency>

        <dependency>
            <groupId>$detectedGroupId</groupId>
            <artifactId>${detectedPrefix}common-encrypt</artifactId>
        </dependency>

        <dependency>
            <groupId>$detectedGroupId</groupId>
            <artifactId>${detectedPrefix}common-tenant</artifactId>
        </dependency>

        <dependency>
            <groupId>$detectedGroupId</groupId>
            <artifactId>${detectedPrefix}common-websocket</artifactId>
        </dependency>
            """.trimIndent(),
            1 // 完整配置排在第二位
        )

        dependencyConfigs.add(basicConfig)
        dependencyConfigs.add(fullConfig)
    }
    
    /**
     * 从项目中检测groupId和artifactId前缀
     * 
     * @return Pair<检测到的groupId, 检测到的前缀>
     */
    private fun detectProjectInfo(): Pair<String, String> {
        var detectedGroupId = "org.dromara" // 默认groupId
        var detectedPrefix = "ruoyi-" // 默认前缀
        
        try {
            // 尝试获取打开的项目
            val projects = ProjectManager.getInstance().openProjects
            if (projects.isEmpty()) {
                return Pair(detectedGroupId, detectedPrefix)
            }
            
            // 使用第一个打开的项目
            val project = projects[0]
            val basePath = project.basePath ?: return Pair(detectedGroupId, detectedPrefix)
            
            // 尝试查找根pom.xml文件
            val rootPomPath = "$basePath/pom.xml"
            val rootPomFile = LocalFileSystem.getInstance().findFileByPath(rootPomPath) ?: return Pair(detectedGroupId, detectedPrefix)
            
            // 解析pom.xml文件
            val psiManager = PsiManager.getInstance(project)
            val psiFile = psiManager.findFile(rootPomFile) as? XmlFile ?: return Pair(detectedGroupId, detectedPrefix)
            
            val rootTag = psiFile.rootTag ?: return Pair(detectedGroupId, detectedPrefix)
            
            // 尝试获取groupId
            var groupId: String? = null
            
            // 直接从根元素获取groupId
            val groupIdTag = rootTag.findFirstSubTag("groupId")
            if (groupIdTag != null) {
                groupId = groupIdTag.value.text
            }
            
            // 如果根元素没有groupId，尝试从parent获取
            if (groupId == null) {
                val parentTag = rootTag.findFirstSubTag("parent")
                if (parentTag != null) {
                    val parentGroupIdTag = parentTag.findFirstSubTag("groupId")
                    if (parentGroupIdTag != null) {
                        groupId = parentGroupIdTag.value.text
                    }
                }
            }
            
            // 如果找到了groupId，更新检测到的值
            if (groupId != null) {
                detectedGroupId = groupId
            }
            
            // 尝试从artifactId确定前缀
            // 查找 ruoyi-common-core 或类似模式的依赖
            val dependencyManagementTag = rootTag.findFirstSubTag("dependencyManagement")
            if (dependencyManagementTag != null) {
                val dependenciesTag = dependencyManagementTag.findFirstSubTag("dependencies")
                if (dependenciesTag != null) {
                    for (dependencyTag in dependenciesTag.findSubTags("dependency")) {
                        val artifactIdTag = dependencyTag.findFirstSubTag("artifactId")
                        if (artifactIdTag != null) {
                            val artifactId = artifactIdTag.value.text
                            if (artifactId.contains("common-core")) {
                                // 找到类似 xxx-common-core 的依赖，提取前缀
                                val index = artifactId.indexOf("common-core")
                                if (index > 0) {
                                    detectedPrefix = artifactId.substring(0, index)
                                    break
                                }
                            }
                        }
                    }
                }
            }
            
            // 如果没有找到合适的前缀，使用 artifactId 推断
            if (detectedPrefix == "ruoyi-") {
                val artifactIdTag = rootTag.findFirstSubTag("artifactId")
                if (artifactIdTag != null) {
                    val artifactId = artifactIdTag.value.text
                    if (artifactId.endsWith("-parent")) {
                        detectedPrefix = artifactId.substring(0, artifactId.length - 7) + "-"
                    }
                }
            }
        } catch (e: Exception) {
            // 忽略所有异常，使用默认值
        }
        
        return Pair(detectedGroupId, detectedPrefix)
    }

    /**
     * 添加配置
     */
    fun addConfig(config: DependencyConfig) {
        // 设置新配置的顺序为当前最大顺序+1
        if (config.order == 0) {
            config.order = dependencyConfigs.maxOfOrNull { it.order }?.plus(1) ?: 0
        }
        dependencyConfigs.add(config)
        sortConfigs()
    }

    /**
     * 删除配置
     */
    fun removeConfig(index: Int) {
        if (index in 0 until dependencyConfigs.size) {
            dependencyConfigs.removeAt(index)
        }
    }

    /**
     * 获取配置名称列表（已排序）
     */
    fun getConfigNames(): List<String> {
        return getSortedConfigs().map { it.name }
    }

    /**
     * 获取指定配置的依赖内容
     */
    fun getDependenciesContent(configName: String): String {
        return dependencyConfigs.find { it.name == configName }?.dependencies ?: ""
    }

    /**
     * 获取排序后的配置列表
     */
    private fun getSortedConfigs(): List<DependencyConfig> {
        return dependencyConfigs.sortedBy { it.order }
    }

    /**
     * 移动配置位置
     */
    fun moveConfig(fromIndex: Int, toIndex: Int) {
        if (fromIndex !in dependencyConfigs.indices || toIndex !in dependencyConfigs.indices) {
            return
        }
        
        val config = dependencyConfigs[fromIndex]
        val targetConfig = dependencyConfigs[toIndex]
        
        // 交换顺序
        val tempOrder = config.order
        config.order = targetConfig.order
        targetConfig.order = tempOrder
        
        sortConfigs()
    }

    /**
     * 对配置进行排序
     */
    private fun sortConfigs() {
        val sorted = dependencyConfigs.sortedBy { it.order }
        dependencyConfigs.clear()
        dependencyConfigs.addAll(sorted)
    }

    /**
     * 更新所有依赖配置中的groupId和前缀
     * 
     * @param groupId 新的groupId
     * @param prefix 新的前缀
     */
    fun updateDependenciesInfo(groupId: String, prefix: String) {
        val updatedConfigs = mutableListOf<DependencyConfig>()
        
        // 更新每个配置中的依赖内容
        for (config in dependencyConfigs) {
            // 替换依赖中的groupId
            var updatedDependencies = config.dependencies.replace("<groupId>org.dromara</groupId>", "<groupId>$groupId</groupId>")
            
            // 替换依赖中的artifactId前缀
            val prefix1 = prefix.trimEnd('-')
            
            // 查找ruoyi-common-xxx模式的依赖并替换
            val regex = Regex("<artifactId>ruoyi-common-([^<]+)</artifactId>")
            updatedDependencies = updatedDependencies.replace(regex) {
                val commonModule = it.groupValues[1]
                "<artifactId>${prefix1}-common-$commonModule</artifactId>"
            }
            
            // 创建更新后的配置
            val updatedConfig = DependencyConfig(
                name = config.name,
                dependencies = updatedDependencies,
                order = config.order
            )
            
            updatedConfigs.add(updatedConfig)
        }
        
        // 更新模块前缀
        modulePrefix = prefix
        
        // 替换所有配置
        dependencyConfigs.clear()
        dependencyConfigs.addAll(updatedConfigs)
        
        // 确保正确排序
        sortConfigs()
    }

    companion object {
        @JvmStatic
        fun getInstance(): DependencyConfigService {
            return ApplicationManager.getApplication().getService(DependencyConfigService::class.java)
        }
    }
} 