package com.github.korykim.ruoyivueplusmodulefastgenerator.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.github.korykim.ruoyivueplusmodulefastgenerator.MyBundle
import com.github.korykim.ruoyivueplusmodulefastgenerator.services.DependencyConfigService
import javax.swing.*
import javax.swing.SwingUtilities

/**
 * 模块名称输入对话框
 * 用户可以在此对话框中输入要创建的新模块名称和选择依赖配置
 */
class ModuleNameInputDialog(project: Project) : DialogWrapper(project) {
    private val moduleNameField = JBTextField(20)
    private val dependencyConfigComboBox = ComboBox<String>()
    private val configService = DependencyConfigService.getInstance()
    
    init {
        title = MyBundle.message("dialog.title.generate.module")
        
        // 加载依赖配置
        refreshConfigComboBox()
        
        // 确保在EDT线程中初始化UI组件
        if (SwingUtilities.isEventDispatchThread()) {
            init()
        } else {
            ApplicationManager.getApplication().invokeLater {
                init()
            }
        }
    }

    override fun createCenterPanel(): JComponent {
        val formPanel = FormBuilder.createFormBuilder()
            .addLabeledComponent(MyBundle.message("dialog.field.module.name"), moduleNameField)
            .addLabeledComponent(MyBundle.message("dialog.field.dependency.config"), dependencyConfigComboBox)
            .panel
            .apply {
                border = JBUI.Borders.empty(10)
            }
        
        val panel = JPanel()
        panel.add(formPanel)
        
        return panel
    }
    
    /**
     * 刷新配置下拉框
     */
    private fun refreshConfigComboBox() {
        dependencyConfigComboBox.removeAllItems()
        configService.getConfigNames().forEach {
            dependencyConfigComboBox.addItem(it)
        }
        
        // 默认选择第一项
        if (dependencyConfigComboBox.itemCount > 0) {
            dependencyConfigComboBox.selectedIndex = 0
        }
    }
    
    /**
     * 获取模块名称
     */
    fun getModuleName(): String = moduleNameField.text
    
    /**
     * 获取选择的依赖配置名称
     */
    fun getSelectedConfigName(): String? = dependencyConfigComboBox.selectedItem as? String
} 