package com.github.korykim.ruoyivueplusmodulefastgenerator.services

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.*
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
        // 基础依赖配置
        val basicConfig = DependencyConfig(
            MyBundle.message("dependency.config.basic"),
            """
        <!-- 基础依赖 -->
        <dependency>
            <groupId>org.dromara</groupId>
            <artifactId>ruoyi-common-core</artifactId>
        </dependency>
        
        <dependency>
            <groupId>org.dromara</groupId>
            <artifactId>ruoyi-common-web</artifactId>
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
            """.trimIndent(),
            1 // 完整配置排在第二位
        )

        dependencyConfigs.add(basicConfig)
        dependencyConfigs.add(fullConfig)
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

    companion object {
        @JvmStatic
        fun getInstance(): DependencyConfigService {
            return ApplicationManager.getApplication().getService(DependencyConfigService::class.java)
        }
    }
} 