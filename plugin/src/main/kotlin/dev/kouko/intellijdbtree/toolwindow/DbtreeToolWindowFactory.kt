package dev.kouko.intellijdbtree.toolwindow

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.jcef.JBCefApp

class DbtreeToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = LineagePanel(project)
        val content = ContentFactory.getInstance().createContent(panel.component, "", false)
        toolWindow.contentManager.addContent(content)
        // The Reset action only matters when there's a JCEF surface to reset;
        // hide it on IDEs/configurations where JCEF is unavailable so users
        // aren't presented with a dud button.
        if (JBCefApp.isSupported()) {
            toolWindow.setTitleActions(listOf(ResetDagAction(panel)))
        }
    }

    override fun shouldBeAvailable(project: Project): Boolean = true
}
