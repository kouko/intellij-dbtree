package dev.kouko.intellijdbtree.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

class DbtreeToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = LineagePanel(project)
        val content = ContentFactory.getInstance().createContent(panel.component, "", false)
        toolWindow.contentManager.addContent(content)
        toolWindow.setTitleActions(listOf(RepaintDagAction(panel)))
    }

    override fun shouldBeAvailable(project: Project): Boolean = true

    /**
     * Manual escape hatch for the JCEF stale-surface bug (JBR-9171 on
     * macOS 26): when fullscreen toggle or monitor change leaves the DAG
     * frozen and the auto-listeners in [LineagePanel.subscribeToSurfaceChanges]
     * miss the event, the user can click this to force a repaint.
     */
    private class RepaintDagAction(private val panel: LineagePanel) : DumbAwareAction(
        "Refresh DAG Display",
        "Force-repaint the lineage DAG (use when the view is stuck after fullscreen toggle or display change)",
        AllIcons.Actions.Refresh,
    ) {
        override fun actionPerformed(e: AnActionEvent) {
            panel.forceRepaint()
        }
    }
}
