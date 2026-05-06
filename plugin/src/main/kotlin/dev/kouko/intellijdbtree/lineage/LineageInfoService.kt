package dev.kouko.intellijdbtree.lineage

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.messages.Topic
import java.util.concurrent.atomic.AtomicReference

/**
 * Subscribers get two distinct events:
 *
 *  - [lineagePayloadChanged] when the DAG topology changes (new active model
 *    falls outside the current DAG, hop limits change, manifest reloads).
 *    The frontend should re-layout.
 *
 *  - [selectedModelChanged] when the active editor file is a model already
 *    inside the current DAG. The frontend should only update its "selected"
 *    highlight — no re-layout, no fitView.
 *
 * Splitting these matches the dbt Power User UX: you can navigate around
 * the DAG by clicking nodes (which opens files) without losing your layout
 * context.
 */
interface LineageInfoListener {
    fun lineagePayloadChanged(payload: LineagePayload) {}
    fun selectedModelChanged(uniqueId: String) {}
}

/**
 * Coordinator that rebuilds and broadcasts lineage on demand.
 *
 * Holds the current focus state — (active model uniqueId, upHops, downHops) —
 * so that hop changes from the UI can re-emit a payload for the same model
 * without needing the editor to do another file selection event.
 */
@Service(Service.Level.PROJECT)
class LineageInfoService(private val project: Project) {

    private val publisher = project.messageBus.syncPublisher(TOPIC)
    private val state = AtomicReference(State())

    data class State(
        val activeUid: String? = null,
        val upHops: Int = DEFAULT_UP_HOPS,
        val downHops: Int = DEFAULT_DOWN_HOPS,
        /** unique_ids in the most recently published full payload. */
        val publishedUids: Set<String> = emptySet(),
    )

    fun snapshot(): State = state.get()

    /**
     * Triggered by editor selection changes. If the file's model is
     * already inside the current DAG, emit a lightweight selection-only
     * event (no re-layout). Otherwise, recenter on this model and emit
     * a full payload.
     */
    fun onActiveFileChanged(file: VirtualFile?) {
        if (file == null || file.extension != "sql") return
        ApplicationManager.getApplication().executeOnPooledThread {
            val manifest = project.service<ManifestService>().ensureLoaded() ?: return@executeOnPooledThread
            val uid = manifest.resolveByOriginalPath(file.path) ?: return@executeOnPooledThread
            val cur = state.get()
            if (uid in cur.publishedUids) {
                // In DAG: only update selection.
                state.set(cur.copy(activeUid = uid))
                publisher.selectedModelChanged(uid)
            } else {
                // Outside DAG: rebuild around this model.
                val updated = cur.copy(activeUid = uid)
                publishFull(manifest, updated)
            }
        }
    }

    /** UI changed up/down hop limits — always re-emit a full payload. */
    fun setHops(upHops: Int, downHops: Int) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val updated = state.updateAndGet {
                it.copy(
                    upHops = upHops.coerceAtLeast(0),
                    downHops = downHops.coerceAtLeast(0),
                )
            }
            val manifest = project.service<ManifestService>().ensureLoaded()
                ?: return@executeOnPooledThread
            publishFull(manifest, updated)
        }
    }

    /** Force a full re-read from disk (e.g. after `dbt parse`). */
    fun refreshFromDisk() {
        ApplicationManager.getApplication().executeOnPooledThread {
            project.service<ManifestService>().refresh()
            val manifest = project.service<ManifestService>().ensureLoaded()
                ?: return@executeOnPooledThread
            publishFull(manifest, state.get())
        }
    }

    /**
     * The user clicked a column in the React UI. Spawn the Python sidecar
     * to compute its full upstream column lineage, then re-publish the
     * payload with `column_edges` populated. Layout doesn't change.
     */
    fun onColumnClicked(modelUid: String, column: String) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val manifest = project.service<ManifestService>().ensureLoaded()
                ?: return@executeOnPooledThread
            val cur = state.get()
            val activeUid = cur.activeUid ?: modelUid

            val basePayload = manifest.buildLineage(activeUid, cur.upHops, cur.downHops)
            val edges = project.service<ColumnLineageService>()
                .computeForColumn(modelUid, column, manifest)

            val payload = basePayload.copy(
                columnEdges = edges,
                selected = Selected(uniqueId = modelUid, column = column),
            )
            // Topology hasn't changed (same nodes / model edges); the
            // frontend's topologyKey memo will skip the re-fit.
            val publishedUids = payload.models.map { it.uniqueId }.toSet()
            state.set(cur.copy(publishedUids = publishedUids))
            publisher.lineagePayloadChanged(payload)
        }
    }

    private fun publishFull(manifest: ParsedManifest, s: State) {
        val payload = if (s.activeUid != null) {
            manifest.buildLineage(s.activeUid, upHops = s.upHops, downHops = s.downHops)
        } else {
            LineagePayload()
        }
        val publishedUids = payload.models.map { it.uniqueId }.toSet()
        state.set(s.copy(publishedUids = publishedUids))
        publisher.lineagePayloadChanged(payload)
    }

    companion object {
        const val DEFAULT_UP_HOPS = 3
        const val DEFAULT_DOWN_HOPS = 3

        @JvmField
        val TOPIC: Topic<LineageInfoListener> = Topic.create(
            "intellij-dbtree LineageInfoTopic",
            LineageInfoListener::class.java,
        )
    }
}
