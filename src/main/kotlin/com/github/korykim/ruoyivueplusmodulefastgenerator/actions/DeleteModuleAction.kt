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
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.fileEditor.FileEditorManager
import java.io.File
import java.lang.reflect.Method
import java.util.concurrent.TimeUnit
import com.intellij.util.concurrency.AppExecutorUtil

/**
 * 删除模块操作
 * 用于右键菜单删除已生成的模块
 */
class DeleteModuleAction : AnAction(), DumbAware {

    private val logger = logger<DeleteModuleAction>()
    
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val selectedFile = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        
        // 尝试提取模块名称
        var moduleName = extractModuleName(selectedFile)
        
        // 如果无法提取模块名称但可能是模块相关，尝试使用文件名
        if (moduleName == null && mightBeModuleRelated(selectedFile)) {
            // 使用文件名作为备选
            moduleName = selectedFile.name
            logger.info("无法提取精确模块名称，使用文件名作为备选: $moduleName")
        }
        
        // 如果仍然无法获取模块名称，退出
        if (moduleName == null) {
            showNotification(project, "无法识别模块名称，请在模块目录上右键", NotificationType.ERROR)
            return
        }
        
        // 显示确认对话框
        val confirmResult = Messages.showYesNoDialog(
            project,
            MyBundle.message("module.delete.confirm.message", moduleName),
            MyBundle.message("module.delete.confirm.title"),
            Messages.getQuestionIcon()
        )
        
        if (confirmResult == Messages.YES) {
            // 记录删除前的路径，用于稍后检查
            val modulePath = selectedFile.path
            
            try {
                // 调用服务删除模块
                val moduleGeneratorService = project.service<ModuleGeneratorService>()
                val success = moduleGeneratorService.deleteModule(moduleName)
                
                if (success) {
                    showNotification(project, MyBundle.message("module.delete.success", moduleName), NotificationType.INFORMATION)
                    
                    // 在删除操作后，调度一个延迟任务来刷新UI，避免可能的"Accessing invalid virtual file"错误
                    schedulePostDeleteRefresh(project, modulePath)
                } else {
                    showNotification(project, MyBundle.message("module.delete.error", "请查看日志获取详细信息"), NotificationType.ERROR)
                }
            } catch (e: Exception) {
                logger.error("删除模块时发生异常: ${e.message}", e)
                showNotification(project, MyBundle.message("module.delete.error", e.message ?: "未知错误"), NotificationType.ERROR)
            }
        }
    }
    
    /**
     * 安排删除后刷新任务
     */
    private fun schedulePostDeleteRefresh(project: Project, deletedPath: String) {
        // 使用EDT线程延迟执行UI刷新
        ApplicationManager.getApplication().invokeLater({
            try {
                // 尝试刷新项目视图 - 使用ToolWindowManager替代直接访问ProjectView
                ToolWindowManager.getInstance(project).let { manager ->
                    manager.getToolWindow("Project")?.activate(null)
                }
                
                // 刷新当前打开的编辑器
                FileEditorManager.getInstance(project).let { fileEditorManager ->
                    val openFiles = fileEditorManager.openFiles
                    for (file in openFiles) {
                        try {
                            if (file.isValid) {
                                file.refresh(false, false)
                            }
                        } catch (e: Exception) {
                            logger.warn("刷新打开的文件时出错: ${e.message}")
                        }
                    }
                }
                
                // 检查并清理任何可能的无效引用
                try {
                    val checkDeleted = LocalFileSystem.getInstance().findFileByPath(deletedPath)
                    if (checkDeleted != null) {
                        if (!checkDeleted.isValid) {
                            logger.warn("发现无效文件引用: $deletedPath，尝试清理")
                            
                            // 尝试通过反射清理无效引用
                            try {
                                val fsClass = Class.forName("com.intellij.openapi.vfs.newvfs.impl.VirtualFileSystemEntry")
                                val invalidateMethod = fsClass.getDeclaredMethod("invalidate")
                                invalidateMethod.isAccessible = true
                                invalidateMethod.invoke(checkDeleted)
                                logger.info("成功调用invalidate方法清理无效引用")
                            } catch (e: Exception) {
                                // 静默处理反射异常
                                logger.debug("通过反射清理无效引用失败: ${e.message}")
                            }
                        }
                        
                        // 刷新父目录
                        val parentPath = File(deletedPath).parent
                        if (parentPath != null) {
                            val parentDir = LocalFileSystem.getInstance().findFileByPath(parentPath)
                            parentDir?.refresh(true, true)
                        }
                    }
                } catch (e: Exception) {
                    logger.warn("处理无效文件引用时出错: ${e.message}")
                }

                // 加入额外的延迟刷新任务，确保完全清理
                try {
                    // 延迟1秒后再次刷新项目
                    AppExecutorUtil.getAppScheduledExecutorService().schedule({
                        ApplicationManager.getApplication().invokeLater {
                            try {
                                // 刷新项目根目录
                                val basePath = project.basePath
                                if (basePath != null) {
                                    val projectDir = LocalFileSystem.getInstance().findFileByPath(basePath)
                                    projectDir?.refresh(true, true)
                                }
                                
                                // 清理所有无效引用
                                project.service<ModuleGeneratorService>().let { service ->
                                    service.javaClass.getDeclaredMethod("scheduleAdditionalRefresh").apply {
                                        isAccessible = true
                                        invoke(service)
                                    }
                                }
                                
                                // 强制GC清理
                                System.gc()
                            } catch (e: Exception) {
                                // 静默处理异常
                                logger.debug("延迟刷新时出错: ${e.message}")
                            }
                        }
                    }, 1, TimeUnit.SECONDS)
                } catch (e: Exception) {
                    // 静默处理延迟任务异常
                    logger.debug("安排延迟刷新任务失败: ${e.message}")
                }
            } catch (e: Exception) {
                logger.warn("删除后刷新UI失败，但这不是致命错误: ${e.message}")
            }
        }, ModalityState.defaultModalityState(), project.disposed)
    }
    
    override fun update(e: AnActionEvent) {
        val project = e.project
        val selectedFile = e.getData(CommonDataKeys.VIRTUAL_FILE)
        
        if (project == null || selectedFile == null) {
            e.presentation.isEnabledAndVisible = false
            return
        }
        
        // 首先尝试提取模块名称
        val moduleName = extractModuleName(selectedFile)
        
        // 如果提取到模块名称，显示菜单并设置动态文本
        if (moduleName != null) {
            e.presentation.text = MyBundle.message("action.delete.module.text", moduleName)
            e.presentation.description = MyBundle.message("action.delete.module.description")
            e.presentation.isEnabledAndVisible = true
            return
        }
        
        // 如果无法提取模块名称，但文件可能是模块目录或其子目录，显示通用的删除菜单
        if (mightBeModuleRelated(selectedFile)) {
            // 使用文件名作为备选名称
            val displayName = selectedFile.name
            e.presentation.text = MyBundle.message("action.delete.module.text", displayName)
            e.presentation.description = MyBundle.message("action.delete.module.description")
            e.presentation.isEnabledAndVisible = true
            return
        }
        
        // 其他情况下隐藏菜单
        e.presentation.isEnabledAndVisible = false
    }
    
    /**
     * 判断文件是否可能与模块相关
     */
    private fun mightBeModuleRelated(file: VirtualFile): Boolean {
        // 检查当前文件
        if (isLikelyModuleDir(file)) {
            return true
        }
        
        // 检查父目录
        var parent = file.parent
        var depth = 0 // 限制向上查找的层数
        
        while (parent != null && depth < 3) { // 最多向上查找3层
            if (isLikelyModuleDir(parent) || parent.name.endsWith("-modules")) {
                return true
            }
            parent = parent.parent
            depth++
        }
        
        return false
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
        
        // 记录详细日志以便调试
        logger.info("尝试从路径提取模块名称: $path, 当前配置的模块前缀: $prefix")
        
        // 尝试多种匹配模式以提高兼容性
        val patterns = arrayListOf(
            // 标准模式：prefix-modules/moduleName
            ".+?/${prefix}-modules/([^/]+)(?:/.*)?".toRegex(),
            // 备用模式1：任何-modules/moduleName
            ".+?/([^/]+)-modules/([^/]+)(?:/.*)?".toRegex(),
            // 备用模式2：直接是模块目录本身
            ".+?/([^/]+)$".toRegex()
        )
        
        for ((index, pattern) in patterns.withIndex()) {
            try {
                val matchResult = pattern.matchEntire(path)
                if (matchResult != null) {
                    // 根据不同模式提取模块名称
                    val moduleName = when (index) {
                        0 -> matchResult.groupValues[1] // 标准模式
                        1 -> matchResult.groupValues[2] // 备用模式1
                        2 -> {
                            // 备用模式2：检查是否为模块目录
                            val dirName = matchResult.groupValues[1]
                            if (file.isDirectory && isLikelyModuleDir(file)) dirName else null
                        }
                        else -> null
                    }
                    
                    if (moduleName != null) {
                        logger.info("成功提取模块名称: $moduleName，使用模式 #$index")
                        return moduleName
                    }
                }
            } catch (e: Exception) {
                logger.warn("使用模式 #$index 提取模块名称时发生异常: ${e.message}")
                // 继续尝试下一个模式
            }
        }
        
        // 所有模式都匹配失败，尝试更直接的方法
        if (file.isDirectory) {
            val name = file.name
            // 检查是否像模块名称（以prefix-开头或者是任何子目录）
            if (name.startsWith("$prefix-") || isLikelyModuleDir(file)) {
                logger.info("通过目录名称直接识别模块: $name")
                return name
            }
            
            // 检查父目录是否是modules目录
            val parent = file.parent
            if (parent != null && (parent.name == "$prefix-modules" || parent.name.endsWith("-modules"))) {
                logger.info("通过父目录识别模块: ${file.name}")
                return file.name
            }
        }
        
        logger.debug("未能从路径提取模块名称: $path")
        return null
    }
    
    /**
     * 判断一个目录是否可能是模块目录
     */
    private fun isLikelyModuleDir(dir: VirtualFile): Boolean {
        if (!dir.isDirectory) return false
        
        // 检查是否包含模块的典型文件或目录
        val hasPomXml = dir.findChild("pom.xml") != null
        val hasSrcDir = dir.findChild("src") != null
        
        // 检查是否在特定命名的父目录下
        val parent = dir.parent
        val inModulesDir = parent != null && parent.name.endsWith("-modules")
        
        return (hasPomXml && hasSrcDir) || inModulesDir
    }
} 