package com.github.korykim.ruoyivueplusmodulefastgenerator.ui

import com.intellij.openapi.options.Configurable
import com.github.korykim.ruoyivueplusmodulefastgenerator.MyBundle
import javax.swing.JComponent

/**
 * 依赖配置设置
 * 用于在IDE设置菜单中注册依赖配置页面
 */
class DependencyConfigSettings : Configurable {
    private var settingsPanel: DependencyConfigSettingsPanel? = null
    
    override fun getDisplayName(): String {
        return MyBundle.message("settings.dependency.config.title")
    }
    
    override fun createComponent(): JComponent? {
        settingsPanel = DependencyConfigSettingsPanel()
        return settingsPanel?.getPanel()
    }
    
    override fun isModified(): Boolean {
        return settingsPanel?.isModified() ?: false
    }
    
    override fun apply() {
        settingsPanel?.apply()
    }
    
    override fun reset() {
        settingsPanel?.reset()
    }
    
    override fun disposeUIResources() {
        settingsPanel = null
    }
} 