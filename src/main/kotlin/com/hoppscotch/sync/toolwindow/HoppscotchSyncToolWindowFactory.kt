package com.hoppscotch.sync.toolwindow

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

@Suppress("DEPRECATION")
class HoppscotchSyncToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = HoppscotchSyncPanel(project)
        val content = ContentFactory.getInstance().createContent(panel.content, "", false)
        toolWindow.contentManager.addContent(content)
    }

    /**
     * 工具窗口对所有项目可见。
     * 显式实现代替继承接口中的废弃默认方法，避免 Plugin Verifier 对
     * IDEA 2026.2+ 的兼容性检查警告。
     */
    override fun isApplicable(project: Project): Boolean = true
}
