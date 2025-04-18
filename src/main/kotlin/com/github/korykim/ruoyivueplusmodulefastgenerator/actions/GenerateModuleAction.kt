package com.github.korykim.ruoyivueplusmodulefastgenerator.actions

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.github.korykim.ruoyivueplusmodulefastgenerator.MyBundle
import com.github.korykim.ruoyivueplusmodulefastgenerator.services.DependencyConfigService
import com.github.korykim.ruoyivueplusmodulefastgenerator.services.ModuleGeneratorService
import com.github.korykim.ruoyivueplusmodulefastgenerator.ui.ModuleNameInputDialog

/**
 * 生成模块操作
 * 用于触发模块生成功能的操作类
 */
class GenerateModuleAction : AnAction(), DumbAware {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        
        // 显示快捷键提示（仅首次使用时）
        if (!isShortcutTipShown) {
            showShortcutTip(project)
            isShortcutTipShown = true
        }
        
        // 确保在EDT线程中显示对话框
        ApplicationManager.getApplication().invokeLater({
            // 显示模块名称输入对话框
            val dialog = ModuleNameInputDialog(project)
            if (dialog.showAndGet()) {
                val moduleName = dialog.getModuleName()
                if (moduleName.isBlank()) {
                    showNotification(project, MyBundle.message("module.name.empty"), NotificationType.ERROR)
                    return@invokeLater
                }
                
                // 获取选定的依赖配置
                val configName = dialog.getSelectedConfigName()
                
                // 获取模块前缀
                val modulePrefix = dialog.getModulePrefix()
                
                // 调用服务生成模块
                val moduleGeneratorService = project.service<ModuleGeneratorService>()
                val success = moduleGeneratorService.generateModule(moduleName, configName, modulePrefix)
                
                if (success) {
                    showNotification(project, MyBundle.message("module.generate.success", moduleName), NotificationType.INFORMATION)
                    
                    // 延迟刷新由ModuleGeneratorService负责，这里不再需要调用
                    // scheduleDelayedRefresh(project, normalizeModuleName(moduleName, modulePrefix))
                } else {
                    showNotification(project, MyBundle.message("module.generate.error", "请查看日志获取详细信息"), NotificationType.ERROR)
                }
            }
        }, ModalityState.defaultModalityState())
    }
    
    /**
     * 标准化模块名称（与ModuleGeneratorService保持一致）
     */
    private fun normalizeModuleName(moduleName: String, modulePrefix: String?): String {
        val prefix = modulePrefix ?: DependencyConfigService.getInstance().modulePrefix // 使用配置的默认前缀
        var name = moduleName.trim()
        if (!name.startsWith(prefix)) {
            name = "$prefix$name"
        }
        return name
    }
    
    override fun update(e: AnActionEvent) {
        // 只在项目中启用该操作
        e.presentation.isEnabledAndVisible = e.project != null
        
        // 更新工具提示，添加快捷键信息
        updateTooltipWithShortcut(e.presentation)
    }
    
    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.BGT
    }
    
    /**
     * 显示通知
     */
    private fun showNotification(project: Project, content: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Module Generator Notification")
            .createNotification(content, type)
            .notify(project)
    }

    /**
     * 显示快捷键提示
     */
    private fun showShortcutTip(project: Project) {
        showNotification(
            project,
            MyBundle.message("action.generate.module.shortcut.tip"),
            NotificationType.INFORMATION
        )
    }

    /**
     * 更新工具提示，添加快捷键信息
     */
    private fun updateTooltipWithShortcut(presentation: Presentation) {
        val description = MyBundle.message("action.generate.module.description")
        presentation.description = description
    }

    companion object {
        private var isShortcutTipShown = false
    }
} 