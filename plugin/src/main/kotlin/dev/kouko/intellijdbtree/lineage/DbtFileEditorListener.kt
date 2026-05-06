package dev.kouko.intellijdbtree.lineage

import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project

/**
 * Bridges IntelliJ's file-editor selection changes to the lineage pipeline.
 * Wired up via the `<projectListeners>` extension in plugin.xml.
 */
class DbtFileEditorListener(private val project: Project) : FileEditorManagerListener {

    override fun selectionChanged(event: FileEditorManagerEvent) {
        val newFile = event.newFile ?: return
        project.service<LineageInfoService>().onActiveFileChanged(newFile)
    }
}
