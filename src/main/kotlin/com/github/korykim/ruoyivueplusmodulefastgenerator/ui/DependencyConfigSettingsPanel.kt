package com.github.korykim.ruoyivueplusmodulefastgenerator.ui

import com.intellij.openapi.ui.Messages
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.github.korykim.ruoyivueplusmodulefastgenerator.MyBundle
import com.github.korykim.ruoyivueplusmodulefastgenerator.services.DependencyConfig
import com.github.korykim.ruoyivueplusmodulefastgenerator.services.DependencyConfigService
import javax.swing.*
import java.awt.BorderLayout
import java.awt.Dimension

/**
 * 依赖配置设置面板
 * 用于在IDE设置中管理模块依赖配置
 */
class DependencyConfigSettingsPanel {
    private val configService = DependencyConfigService.getInstance()
    private val configListModel = DefaultListModel<String>()
    private val configList = JBList(configListModel)
    private val dependencyTextArea = JBTextArea()
    private val configNameField = JTextField()
    private var isModified = false
    private val mainPanel: JPanel
    
    init {
        // 初始化配置列表
        refreshConfigList()
        
        // 创建依赖编辑区域
        dependencyTextArea.lineWrap = true
        dependencyTextArea.wrapStyleWord = true
        val dependencyScrollPane = JBScrollPane(dependencyTextArea)
        dependencyScrollPane.preferredSize = Dimension(400, 300)
        
        // 创建配置列表区域
        configList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        configList.addListSelectionListener { 
            if (!it.valueIsAdjusting && configList.selectedIndex >= 0) {
                loadSelectedConfig()
            }
        }
        
        val listDecorator = ToolbarDecorator.createDecorator(configList)
            .setAddAction { addConfig() }
            .setRemoveAction { removeSelectedConfig() }
            .setMoveUpAction { moveSelectedConfig(-1) }
            .setMoveDownAction { moveSelectedConfig(1) }
        
        // 配置名称输入
        val namePanel = JPanel(BorderLayout())
        namePanel.add(JLabel(MyBundle.message("settings.config.name")), BorderLayout.WEST)
        namePanel.add(configNameField, BorderLayout.CENTER)
        
        // 保存按钮
        val saveButton = JButton(MyBundle.message("settings.config.save"))
        saveButton.addActionListener {
            saveCurrentConfig()
        }
        
        // 布局
        val leftPanel = listDecorator.createPanel()
        leftPanel.preferredSize = Dimension(200, 300)
        
        val rightPanel = FormBuilder.createFormBuilder()
            .addComponent(namePanel)
            .addComponent(
                panel {
                    row(MyBundle.message("settings.config.dependencies")) {
                        cell(dependencyScrollPane)
                            .align(com.intellij.ui.dsl.builder.AlignX.FILL)
                    }
                }
            )
            .addComponent(saveButton)
            .panel
        
        mainPanel = JPanel(BorderLayout()).apply {
            add(leftPanel, BorderLayout.WEST)
            add(rightPanel, BorderLayout.CENTER)
            border = JBUI.Borders.empty(10)
        }
        
        // 如果有配置，默认选择第一个
        if (configListModel.size() > 0) {
            configList.selectedIndex = 0
            loadSelectedConfig()
        }
    }
    
    /**
     * 获取主面板
     */
    fun getPanel(): JPanel {
        return mainPanel
    }
    
    /**
     * 是否已修改
     */
    fun isModified(): Boolean {
        return isModified
    }
    
    /**
     * 应用设置
     */
    fun apply() {
        isModified = false
    }
    
    /**
     * 重置设置
     */
    fun reset() {
        refreshConfigList()
        isModified = false
    }
    
    /**
     * 刷新配置列表
     */
    private fun refreshConfigList() {
        configListModel.clear()
        configService.getConfigNames().forEach {
            configListModel.addElement(it)
        }
    }
    
    /**
     * 加载选中的配置
     */
    private fun loadSelectedConfig() {
        val selectedName = configList.selectedValue ?: return
        configNameField.text = selectedName
        dependencyTextArea.text = configService.getDependenciesContent(selectedName)
    }
    
    /**
     * 添加新配置
     */
    private fun addConfig() {
        val name = Messages.showInputDialog(
            mainPanel,
            MyBundle.message("settings.config.add.message"),
            MyBundle.message("settings.config.add.title"),
            null
        ) ?: return
        
        if (name.isBlank()) {
            Messages.showErrorDialog(
                MyBundle.message("settings.config.error.name.empty"),
                MyBundle.message("settings.config.add.title")
            )
            return
        }
        
        if (configService.getConfigNames().contains(name)) {
            Messages.showErrorDialog(
                MyBundle.message("settings.config.error.name.exists"),
                MyBundle.message("settings.config.add.title")
            )
            return
        }
        
        configService.addConfig(DependencyConfig(name, ""))
        refreshConfigList()
        configList.selectedIndex = configListModel.size() - 1
        isModified = true
    }
    
    /**
     * 移除选中的配置
     */
    private fun removeSelectedConfig() {
        val index = configList.selectedIndex
        if (index < 0) return
        
        val result = Messages.showYesNoDialog(
            MyBundle.message("settings.config.delete.message"),
            MyBundle.message("settings.config.delete.title"),
            null
        )
        
        if (result == Messages.YES) {
            configService.removeConfig(index)
            refreshConfigList()
            if (configListModel.size() > 0) {
                configList.selectedIndex = 0
            } else {
                configNameField.text = ""
                dependencyTextArea.text = ""
            }
            isModified = true
        }
    }
    
    /**
     * 保存当前配置
     */
    private fun saveCurrentConfig() {
        val index = configList.selectedIndex
        if (index < 0) return
        
        val newName = configNameField.text
        if (newName.isBlank()) {
            Messages.showErrorDialog(
                MyBundle.message("settings.config.error.name.empty"),
                MyBundle.message("settings.config.add.title")
            )
            return
        }
        
        val oldName = configList.selectedValue
        if (newName != oldName && configService.getConfigNames().contains(newName)) {
            Messages.showErrorDialog(
                MyBundle.message("settings.config.error.name.exists"),
                MyBundle.message("settings.config.add.title")
            )
            return
        }
        
        // 更新配置
        val config = DependencyConfig(newName, dependencyTextArea.text)
        configService.removeConfig(index)
        configService.addConfig(config)
        
        refreshConfigList()
        for (i in 0 until configListModel.size()) {
            if (configListModel.getElementAt(i) == newName) {
                configList.selectedIndex = i
                break
            }
        }
        
        isModified = true
        Messages.showInfoMessage(
            MyBundle.message("settings.config.save.success"),
            MyBundle.message("settings.config.save.success.title")
        )
    }
    
    /**
     * 移动选中的配置
     * @param direction -1表示向上移动，1表示向下移动
     */
    private fun moveSelectedConfig(direction: Int) {
        val selectedIndex = configList.selectedIndex
        if (selectedIndex < 0) return

        val newIndex = selectedIndex + direction
        if (newIndex in 0 until configListModel.size()) {
            configService.moveConfig(selectedIndex, newIndex)
            refreshConfigList()
            configList.selectedIndex = newIndex
            isModified = true
        }
    }
} 