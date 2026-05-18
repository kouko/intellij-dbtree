package dev.kouko.intellijdbtree.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware

/**
 * Title-bar action that forces a full reload of the JCEF browser.
 *
 * Manual recovery path for the macOS 26 Tahoe freeze (JBR-9171). The
 * automatic [ApplicationActivationListener] in [LineagePanel] handles
 * most cases, but a visible button gives users an explicit fallback
 * without resorting to custom VM options.
 */
class ResetDagAction(private val panel: LineagePanel) :
    AnAction(
        "Reset DAG Panel",
        "Reload the lineage view if it froze after the IDE was backgrounded. " +
            "DAG content and hop settings are preserved; viewport and column-trace highlights will reset.",
        AllIcons.Actions.Refresh,
    ),
    DumbAware {

    override fun actionPerformed(e: AnActionEvent) {
        panel.resetBrowser()
    }
}
