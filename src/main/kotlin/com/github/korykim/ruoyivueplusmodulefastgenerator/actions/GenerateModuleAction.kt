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
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.util.concurrency.AppExecutorUtil
import org.jetbrains.idea.maven.project.MavenProjectsManager
import java.util.concurrent.TimeUnit
import com.github.korykim.ruoyivueplusmodulefastgenerator.MyBundle
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
                    
                    // 延迟刷新确保Maven项目导入完成后UI更新
                    scheduleDelayedRefresh(project, normalizeModuleName(moduleName, modulePrefix))
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
        val prefix = modulePrefix ?: "ruoyi-" // 使用默认前缀
        var name = moduleName.trim()
        if (!name.startsWith(prefix)) {
            name = "$prefix$name"
        }
        return name
    }
    
    /**
     * 安排延迟刷新任务，确保Maven项目导入完成后能够正确显示新模块
     */
    private fun scheduleDelayedRefresh(project: Project, moduleName: String) {
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
                    val modulePath = "$basePath/ruoyi-modules/$moduleName"
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
            // 忽略异常，不影响用户体验
        }
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