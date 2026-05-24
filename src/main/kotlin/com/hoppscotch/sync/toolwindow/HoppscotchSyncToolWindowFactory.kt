package com.hoppscotch.sync.toolwindow

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

class HoppscotchSyncToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = HoppscotchSyncPanel(project)
        val content = ContentFactory.getInstance().createContent(panel.content, "", false)
        toolWindow.contentManager.addContent(content)
    }
}
