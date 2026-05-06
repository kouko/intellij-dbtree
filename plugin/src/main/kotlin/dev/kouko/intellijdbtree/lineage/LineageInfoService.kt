package dev.kouko.intellijdbtree.lineage

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.messages.Topic

/** Subscribers receive a fresh [LineagePayload] whenever lineage state changes. */
fun interface LineageInfoListener {
    fun lineagePayloadChanged(payload: LineagePayload)
}

/**
 * Coordinator that rebuilds and broadcasts lineage on demand.
 *
 * The flow:
 *   FileEditorListener -> setActiveFile(file)
 *   -> ManifestService.resolveModel(file)
 *   -> ManifestService.lineageFor(uniqueId)
 *   -> publish to TOPIC
 *   -> LineagePanel pushes to JCEF via setLineageInfo(...)
 */
@Service(Service.Level.PROJECT)
class LineageInfoService(private val project: Project) {

    private val publisher = project.messageBus.syncPublisher(TOPIC)

    /** Trigger by editor selection changes; runs lineage build off-EDT. */
    fun onActiveFileChanged(file: VirtualFile?) {
        if (file == null || file.extension != "sql") return
        ApplicationManager.getApplication().executeOnPooledThread {
            val manifest = project.service<ManifestService>().ensureLoaded() ?: return@executeOnPooledThread
            val uid = manifest.resolveByOriginalPath(file.path) ?: return@executeOnPooledThread
            val payload = manifest.buildLineage(uid)
            publisher.lineagePayloadChanged(payload)
        }
    }

    /** Force a full re-read from disk (e.g. after `dbt parse`). */
    fun refreshFromDisk() {
        ApplicationManager.getApplication().executeOnPooledThread {
            project.service<ManifestService>().refresh()
        }
    }

    companion object {
        @JvmField
        val TOPIC: Topic<LineageInfoListener> = Topic.create(
            "intellij-dbtree LineageInfoTopic",
            LineageInfoListener::class.java,
        )
    }
}
