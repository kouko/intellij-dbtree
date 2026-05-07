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
    /**
     * A model's column list was lazily computed (e.g. via the sqlglot sidecar
     * after the user expanded a card with empty columns). Subscribers should
     * surgically patch the payload's model entry without re-layout.
     */
    fun modelColumnsUpdated(uniqueId: String, columns: List<ColumnSpec>) {}
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
        /**
         * Monotonic counter bumped at every public entry point that triggers
         * async work. Each pooled task captures the value at dispatch and
         * checks it again before publishing — if the user fired a newer
         * intent in the meantime, the late task drops its result instead of
         * stomping on the fresher payload.
         *
         * Without this, a slow `onActiveFileChanged` for file A followed by
         * a fast one for file B can publish in either order, and the user's
         * canvas may snap back to A after they've moved on to B.
         */
        val epoch: Long = 0,
    )

    fun snapshot(): State = state.get()

    /** Increments [State.epoch] atomically and returns the new value. */
    private fun bumpEpoch(): Long = state.updateAndGet { it.copy(epoch = it.epoch + 1) }.epoch

    /**
     * Triggered by editor selection changes. If the file's model is
     * already inside the current DAG, emit a lightweight selection-only
     * event (no re-layout). Otherwise, recenter on this model and emit
     * a full payload.
     */
    fun onActiveFileChanged(file: VirtualFile?) {
        if (file == null || file.extension != "sql") return
        val myEpoch = bumpEpoch()
        ApplicationManager.getApplication().executeOnPooledThread {
            if (project.isDisposed) return@executeOnPooledThread
            val manifest = project.service<ManifestService>().ensureLoaded() ?: return@executeOnPooledThread
            val uid = manifest.resolveByOriginalPath(file.path) ?: return@executeOnPooledThread
            val cur = state.get()
            if (isSuperseded(myEpoch, cur)) return@executeOnPooledThread
            when (val decision = decideFocusEvent(uid, cur.publishedUids)) {
                is FocusDecision.SelectionOnly -> {
                    // updateAndGet (not set) so we don't roll back fields a
                    // newer racing task may have written between the epoch
                    // check and here — including the epoch itself.
                    state.updateAndGet { it.copy(activeUid = decision.uid) }
                    publisher.selectedModelChanged(decision.uid)
                }
                is FocusDecision.Rebuild -> {
                    publishFull(manifest, decision.uid)
                }
            }
        }
    }

    /** UI changed up/down hop limits — always re-emit a full payload. */
    fun setHops(upHops: Int, downHops: Int) {
        // Bump epoch + write hops atomically on the calling thread so a
        // rapid slider drag (multiple events per second) can't race the
        // hop value ahead of the epoch.
        val updated = state.updateAndGet {
            it.copy(
                upHops = upHops.coerceAtLeast(0),
                downHops = downHops.coerceAtLeast(0),
                epoch = it.epoch + 1,
            )
        }
        val myEpoch = updated.epoch
        ApplicationManager.getApplication().executeOnPooledThread {
            if (project.isDisposed) return@executeOnPooledThread
            val manifest = project.service<ManifestService>().ensureLoaded()
                ?: return@executeOnPooledThread
            if (isSuperseded(myEpoch, state.get())) return@executeOnPooledThread
            publishFull(manifest, state.get().activeUid)
        }
    }

    /** Force a full re-read from disk (e.g. after `dbt parse`). */
    fun refreshFromDisk() {
        val myEpoch = bumpEpoch()
        ApplicationManager.getApplication().executeOnPooledThread {
            if (project.isDisposed) return@executeOnPooledThread
            project.service<ManifestService>().refresh()
            project.service<ColumnLineageService>().invalidateColumnListCache()
            val manifest = project.service<ManifestService>().ensureLoaded()
                ?: return@executeOnPooledThread
            if (isSuperseded(myEpoch, state.get())) return@executeOnPooledThread
            publishFull(manifest, state.get().activeUid)
        }
    }

    /**
     * The user expanded a model card whose column list was empty. Spawn
     * the Python sidecar to extract output columns from the model's
     * compiled SQL via sqlglot, then publish the per-model patch event.
     */
    fun onRequestColumns(modelUid: String) {
        val myEpoch = bumpEpoch()
        ApplicationManager.getApplication().executeOnPooledThread {
            if (project.isDisposed) return@executeOnPooledThread
            val manifest = project.service<ManifestService>().ensureLoaded()
                ?: return@executeOnPooledThread
            val names = project.service<ColumnLineageService>()
                .listColumnsViaSidecar(modelUid, manifest)
                ?: return@executeOnPooledThread
            if (project.isDisposed) return@executeOnPooledThread
            if (isSuperseded(myEpoch, state.get())) return@executeOnPooledThread
            val columns = names.map { ColumnSpec(name = it) }
            publisher.modelColumnsUpdated(modelUid, columns)
        }
    }

    /**
     * The user clicked a column in the React UI. Spawn the Python sidecar
     * to compute its full upstream column lineage, then re-publish the
     * payload with `column_edges` populated. Layout doesn't change.
     */
    fun onColumnClicked(modelUid: String, column: String) {
        val myEpoch = bumpEpoch()
        ApplicationManager.getApplication().executeOnPooledThread {
            if (project.isDisposed) return@executeOnPooledThread
            val manifest = project.service<ManifestService>().ensureLoaded()
                ?: return@executeOnPooledThread
            val cur = state.get()
            val activeUid = cur.activeUid ?: modelUid

            val basePayload = manifest.buildLineage(activeUid, cur.upHops, cur.downHops)
            val result = project.service<ColumnLineageService>()
                .computeForColumn(modelUid, column, manifest)

            // Sidecar takes ~1s — the user may have clicked another column,
            // switched files, or closed the project in the meantime. Re-check
            // both before publishing.
            if (project.isDisposed) return@executeOnPooledThread
            if (isSuperseded(myEpoch, state.get())) return@executeOnPooledThread

            val (edges, warning) = when (result) {
                is ColumnLineageService.Result.Ok -> result.edges to null
                is ColumnLineageService.Result.Failed -> emptyList<ColumnEdge>() to result.warning
            }

            val payload = basePayload.copy(
                columnEdges = edges,
                selected = Selected(uniqueId = modelUid, column = column),
                warning = warning,
            )
            // Topology hasn't changed (same nodes / model edges); the
            // frontend's topologyKey memo will skip the re-fit.
            val publishedUids = payload.models.map { it.uniqueId }.toSet()
            state.updateAndGet { it.copy(publishedUids = publishedUids) }
            publisher.lineagePayloadChanged(payload)
        }
    }

    /**
     * Build + publish a full lineage payload centered on [newActiveUid] (or
     * an empty payload when null). Reads the latest hops off [state] so it
     * always uses the freshest UI value, and writes back only the fields it
     * owns ([State.activeUid] + [State.publishedUids]) to avoid clobbering
     * concurrent updates from other entry points.
     */
    private fun publishFull(manifest: ParsedManifest, newActiveUid: String?) {
        val cur = state.get()
        val payload = if (newActiveUid != null) {
            manifest.buildLineage(newActiveUid, upHops = cur.upHops, downHops = cur.downHops)
        } else {
            LineagePayload()
        }
        val publishedUids = payload.models.map { it.uniqueId }.toSet()
        state.updateAndGet { it.copy(activeUid = newActiveUid, publishedUids = publishedUids) }
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
