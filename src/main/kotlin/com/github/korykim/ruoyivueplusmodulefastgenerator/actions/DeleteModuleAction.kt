package com.github.korykim.ruoyivueplusmodulefastgenerator.actions

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import com.github.korykim.ruoyivueplusmodulefastgenerator.MyBundle
import com.github.korykim.ruoyivueplusmodulefastgenerator.services.DependencyConfigService
import com.github.korykim.ruoyivueplusmodulefastgenerator.services.ModuleGeneratorService

/**
 * 删除模块操作
 * 用于右键菜单删除已生成的模块
 */
class DeleteModuleAction : AnAction(), DumbAware {

    private val logger = logger<DeleteModuleAction>()
    
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val selectedFile = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        
        // 提取模块名称
        val moduleName = extractModuleName(selectedFile) ?: return
        
        // 显示确认对话框
        val confirmResult = Messages.showYesNoDialog(
            project,
            MyBundle.message("module.delete.confirm.message", moduleName),
            MyBundle.message("module.delete.confirm.title"),
            Messages.getQuestionIcon()
        )
        
        if (confirmResult == Messages.YES) {
            // 调用服务删除模块
            val moduleGeneratorService = project.service<ModuleGeneratorService>()
            val success = moduleGeneratorService.deleteModule(moduleName)
            
            if (success) {
                showNotification(project, MyBundle.message("module.delete.success", moduleName), NotificationType.INFORMATION)
            } else {
                showNotification(project, MyBundle.message("module.delete.error", "请查看日志获取详细信息"), NotificationType.ERROR)
            }
        }
    }
    
    override fun update(e: AnActionEvent) {
        val project = e.project
        val selectedFile = e.getData(CommonDataKeys.VIRTUAL_FILE)
        
        // 只有在模块目录上右键时才显示此菜单项
        if (project != null && selectedFile != null) {
            val moduleName = extractModuleName(selectedFile)
            if (moduleName != null) {
                // 更新菜单项文本，包含模块名称
                e.presentation.text = MyBundle.message("action.delete.module.text", moduleName)
                e.presentation.description = MyBundle.message("action.delete.module.description")
                e.presentation.isEnabledAndVisible = true
            } else {
                e.presentation.isEnabledAndVisible = false
            }
        } else {
            e.presentation.isEnabledAndVisible = false
        }
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
     * 从文件对象中提取模块名称
     * 根据配置的模块前缀动态识别模块目录
     */
    private fun extractModuleName(file: VirtualFile): String? {
        val path = file.path
        
        // 获取当前配置的模块前缀，并移除结尾的连字符（如果有）
        val prefix = DependencyConfigService.getInstance().modulePrefix.trimEnd('-')
        
        // 动态构建匹配模式：{prefix}-modules/{moduleName}
        val modulesPattern = ".+?/${prefix}-modules/([^/]+)(?:/.*)?".toRegex()
        
        try {
            val matchResult = modulesPattern.matchEntire(path)
            val moduleName = matchResult?.groupValues?.get(1)
            
            // 记录日志，帮助调试
            if (moduleName != null) {
                logger.debug("成功提取模块名称: $moduleName，路径: $path")
            } else {
                logger.debug("未能从路径提取模块名称: $path，使用的模式: ${modulesPattern.pattern}")
            }
            
            return moduleName
        } catch (e: Exception) {
            // 处理可能的异常情况，确保不会因为模式匹配失败而导致插件崩溃
            logger.warn("提取模块名称时发生异常: ${e.message}, 路径: $path")
            return null
        }
    }
} 