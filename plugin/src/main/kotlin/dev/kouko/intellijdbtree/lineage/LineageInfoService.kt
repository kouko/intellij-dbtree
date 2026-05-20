package dev.kouko.intellijdbtree.lineage

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.messages.Topic
import java.util.concurrent.TimeUnit
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

    /**
     * Streaming column-edges patch — sent during a column-lineage
     * trace every [LineageInfoService.STREAM_PUBLISH_INTERVAL_MS] ms
     * (intermediate, done=false) and once at the end (done=true,
     * optional warning). Subscribers should fold this onto the active
     * payload's `column_edges` without re-laying out — topology
     * doesn't change between the trace-start publish (a regular
     * [lineagePayloadChanged] with empty column_edges) and these
     * delta events.
     */
    fun columnEdgesAppended(delta: ColumnEdgesDelta) {}
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
        /**
         * Currently-selected model — drifts with editor selection
         * (in-view navigation, file-open side effect of NODE_CLICK).
         * Used by [setHops] and [refreshFromDisk] as the seed for
         * `publishFull` so toolbar refresh / hop changes recenter on
         * what the user is actually looking at.
         */
        val activeUid: String? = null,
        /**
         * Anchored DAG center — only written by [publishFull]. Never
         * drifts on in-view selection. Used by the column-lineage
         * trace's snapshot so a column click doesn't churn topology
         * (the bug fixed by `ce5e644` + this field's introduction).
         */
        val centerUid: String? = null,
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
     * Get the loaded manifest, or — if loading failed — publish an empty
     * payload carrying the failure reason so the React panel can display
     * a "run dbt parse" / "manifest.json failed to parse" banner via its
     * existing [LineagePayload.warning] surface.
     *
     * Replaces the old `ensureLoaded() ?: return` pattern, which silently
     * left the canvas blank when the manifest was missing or broken — the
     * #1 confusion point for first-time users.
     */
    private fun ensureManifestOrPublishStatus(): ParsedManifest? {
        val ms = project.service<ManifestService>()
        ms.ensureLoaded()?.let { return it }
        val warning = manifestStatusWarning(ms.lastRefreshResult()) ?: return null
        publisher.lineagePayloadChanged(LineagePayload(warning = warning))
        state.updateAndGet { it.copy(publishedUids = emptySet()) }
        return null
    }

    /**
     * Triggered by editor selection changes. If the file's model is
     * already inside the current DAG, emit a lightweight selection-only
     * event (no re-layout). Otherwise, recenter on this model and emit
     * a full payload.
     */
    fun onActiveFileChanged(file: VirtualFile?) {
        if (file == null || file.extension != "sql") return
        ApplicationManager.getApplication().executeOnPooledThread {
            if (project.isDisposed) return@executeOnPooledThread
            val manifest = ensureManifestOrPublishStatus() ?: return@executeOnPooledThread
            val uid = manifest.resolveByOriginalPath(file.path) ?: return@executeOnPooledThread
            val cur = state.get()
            when (val decision = decideFocusEvent(uid, cur.publishedUids)) {
                is FocusDecision.SelectionOnly -> {
                    // Selection within the current DAG: track it in
                    // activeUid so the toolbar refresh / hop slider
                    // rebuild around whatever the user is actually
                    // looking at. Do NOT touch centerUid — that's
                    // anchored to the most recent publishFull seed and
                    // is what the column-trace snapshot reads, so
                    // tracing while navigating doesn't churn topology.
                    //
                    // No epoch bump here — selection is not a
                    // topology-changing event and must not supersede a
                    // long-running column-lineage trace. (Symptom of
                    // the original bug: clicking a column, then
                    // clicking the same column again, the second click
                    // would show no edges because the file-already-open
                    // path skipped onActiveFileChanged entirely;
                    // keeping epoch stable avoids that.)
                    //
                    // updateAndGet (not set) so we don't roll back
                    // fields a newer racing task may have written
                    // between here and the state read above.
                    state.updateAndGet { stateAfterSelectionOnly(it, decision.uid) }
                    publisher.selectedModelChanged(decision.uid)
                }
                is FocusDecision.Rebuild -> {
                    // A real topology change — bump epoch HERE so older
                    // rebuilds and older column traces are correctly
                    // superseded by this newer focal-model switch.
                    val myEpoch = bumpEpoch()
                    if (isSuperseded(myEpoch, state.get())) return@executeOnPooledThread
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
            val manifest = ensureManifestOrPublishStatus() ?: return@executeOnPooledThread
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
            PythonInterpreterResolver.invalidateValidationCache()
            val manifest = ensureManifestOrPublishStatus() ?: return@executeOnPooledThread
            if (isSuperseded(myEpoch, state.get())) return@executeOnPooledThread
            publishFull(manifest, state.get().activeUid)
        }
    }

    /**
     * The user expanded a model card whose column list was empty, or
     * the frontend's prefetch effect requested columns at DAG-load
     * time. Spawn the Python sidecar to extract output columns from
     * the model's compiled SQL via sqlglot, then publish the
     * per-model patch event.
     *
     * Epoch handling: column requests neither bump nor consult the
     * epoch. Bumping would supersede in-flight payload-changing work
     * (active-file change, hop change, column-click trace); consulting
     * would let those events kill in-flight column requests — and
     * since the frontend prefetches columns for every visible card
     * shortly after a payload lands, a column click that arrives a few
     * hundred ms later would stomp the entire prefetch batch, leaving
     * the affected cards stuck on "Parsing SQL…" forever.
     *
     * No publishedUids gate: this used to skip the publish when the model
     * had been navigated away from while sqlglot was running, on the theory
     * that `applyModelColumns` would be a no-op for an absent model. That
     * theory was wrong — `applyModelColumns` ALSO clears the React
     * `pendingColumns` Set as a side effect, so dropping the publish leaves
     * the uid stuck in `pendingColumns` forever, inflating the toolbar
     * "Parsing N" indicator until the 90s safety net fires. With heavy
     * navigation, ~60% of publishes were being silently dropped and the
     * counter stayed stuck at large values (observed: 73 / 123 emitted).
     *
     * The IPC saving was negligible (one executeJavaScript per navigated-
     * away uid, microseconds); the visible-payload model.map() is a no-op
     * on the React side. Always publish.
     */
    fun onRequestColumns(modelUid: String) {
        ApplicationManager.getApplication().executeOnPooledThread {
            if (project.isDisposed) return@executeOnPooledThread
            val manifest = ensureManifestOrPublishStatus() ?: return@executeOnPooledThread
            // Always publish, even on sidecar failure (null) — coerce to
            // an empty column list. Otherwise the frontend has no signal
            // that the request completed and the card stays on
            // "Parsing SQL…" forever (e.g. when no Python interpreter is
            // configured, or sqlglot can't parse the model). The frontend's
            // `attemptedColumns` set treats any response — empty or full —
            // as terminal so the prefetch effect doesn't loop on the empty
            // case. The user can still force a re-fetch by collapse +
            // expand on the card.
            val names = project.service<ColumnLineageService>()
                .listColumnsViaSidecar(modelUid, manifest)
                ?: emptyList()
            if (project.isDisposed) return@executeOnPooledThread
            val columns = names.map { ColumnSpec(name = it) }
            publisher.modelColumnsUpdated(modelUid, columns)
        }
    }

    /**
     * The user clicked a column in the React UI. Spawn the Python sidecar
     * in --stream mode and republish the payload progressively as edges
     * are discovered:
     *
     *   1. Immediate publish: empty column_edges, columnLineageDone=false,
     *      so the toolbar shows "computing column lineage…" without delay.
     *   2. Debounced publish (every [STREAM_PUBLISH_INTERVAL_MS]ms or on
     *      done): partial column_edges, columnLineageDone=false. React
     *      paints edges incrementally so the user sees progress instead
     *      of waiting in the dark.
     *   3. Final publish: full column_edges, columnLineageDone=true,
     *      warning (if any). Clears the "computing…" hint.
     *
     * Topology never changes here — the same DAG nodes / model edges
     * are reused across all three publishes. React's topologyKey memo
     * skips the re-fit.
     */
    fun onColumnClicked(modelUid: String, column: String) {
        val myEpoch = bumpEpoch()
        // Snapshot center + hops on the calling (CEF) thread BEFORE the
        // racing NODE_CLICK side-channel can land. NODE_CLICK opens the
        // column's owner file → FileEditorManagerListener fires
        // selectionChanged → onActiveFileChanged → state.activeUid gets
        // updated to the clicked model. If onColumnClicked's pooled
        // thread reads state after that update, it builds basePayload
        // around a different model than the user's pre-click DAG and
        // the 3 streamed publishes ship a different topology — user
        // sees cards appear / disappear mid-trace.
        //
        // Snapshotting on the CEF thread (sequential with JS callbacks)
        // freezes values before any of NODE_CLICK's downstream thread
        // hops complete. We anchor to centerUid (the last publishFull
        // seed, never drifts on in-view selection), not activeUid
        // (which does drift) — so even across multiple column clicks
        // the trace stays centered on the DAG the user explicitly
        // built via refresh / hop change.
        val snap = state.get()
        val snapCenterUid = snap.centerUid ?: snap.activeUid ?: modelUid
        val snapUpHops = snap.upHops
        val snapDownHops = snap.downHops
        ApplicationManager.getApplication().executeOnPooledThread {
            if (project.isDisposed) return@executeOnPooledThread
            val manifest = ensureManifestOrPublishStatus() ?: return@executeOnPooledThread
            val basePayload = manifest.buildLineage(snapCenterUid, snapUpHops, snapDownHops)
            val selected = Selected(uniqueId = modelUid, column = column)
            val publishedUids = basePayload.models.map { it.uniqueId }.toSet()
            state.updateAndGet { it.copy(publishedUids = publishedUids) }

            // Step 1: immediate "computing…" publish.
            if (isSuperseded(myEpoch, state.get())) return@executeOnPooledThread
            publisher.lineagePayloadChanged(
                basePayload.copy(
                    columnEdges = emptyList(),
                    selected = selected,
                    columnLineageDone = false,
                ),
            )

            // Step 2: stream edges, debounced republish. Each flush
            // pushes ONLY the edges discovered since the previous flush
            // (not the whole accumulated list), and only via the
            // delta-event channel — topology and `selected` were already
            // established by Step 1. This is the load-bearing change that
            // unfreezes the dbtree panel on iCHEF-sized projects: a 30s
            // trace used to ship ~60 multi-MB full payloads through
            // JCEF's `executeJavaScript` literal-source path; now it
            // ships ~60 small edge-only payloads.
            val edges = mutableListOf<ColumnEdge>()
            var lastFlushNanos = System.nanoTime()
            var lastFlushedCount = 0
            val flushIntervalNanos = TimeUnit.MILLISECONDS.toNanos(STREAM_PUBLISH_INTERVAL_MS)
            val flushLock = Any()

            val outcome = project.service<ColumnLineageService>().computeForColumnStream(
                modelUid,
                column,
                manifest,
                onEdge = { edge ->
                    synchronized(flushLock) {
                        edges.add(edge)
                        val now = System.nanoTime()
                        if (now - lastFlushNanos >= flushIntervalNanos) {
                            lastFlushNanos = now
                            if (!project.isDisposed && !isSuperseded(myEpoch, state.get())) {
                                val newEdges = edges.subList(lastFlushedCount, edges.size).toList()
                                lastFlushedCount = edges.size
                                if (newEdges.isNotEmpty()) {
                                    publisher.columnEdgesAppended(
                                        ColumnEdgesDelta(
                                            appendEdges = newEdges,
                                            columnLineageDone = false,
                                            warning = null,
                                        ),
                                    )
                                }
                            }
                        }
                    }
                },
            )

            // Step 3: final publish (any leftover edges + done=true +
            // optional warning). Same delta channel as Step 2.
            if (project.isDisposed) return@executeOnPooledThread
            if (isSuperseded(myEpoch, state.get())) return@executeOnPooledThread

            val (tailEdges, warning) = synchronized(flushLock) {
                val tail = edges.subList(lastFlushedCount, edges.size).toList()
                lastFlushedCount = edges.size
                val w = when (outcome) {
                    is ColumnLineageService.StreamOutcome.Ok ->
                        outcome.notice?.takeIf { it.isNotBlank() && edges.isEmpty() }
                    is ColumnLineageService.StreamOutcome.Failed -> outcome.warning
                }
                tail to w
            }

            publisher.columnEdgesAppended(
                ColumnEdgesDelta(
                    appendEdges = tailEdges,
                    columnLineageDone = true,
                    warning = warning,
                ),
            )
        }
    }

    /**
     * Build + publish a full lineage payload centered on [newActiveUid] (or
     * an empty payload when null). Reads the latest hops off [state] so it
     * always uses the freshest UI value, and writes back only the fields it
     * owns ([State.activeUid] + [State.centerUid] + [State.publishedUids])
     * to avoid clobbering concurrent updates from other entry points.
     */
    private fun publishFull(manifest: ParsedManifest, newActiveUid: String?) {
        val cur = state.get()
        val payload = if (newActiveUid != null) {
            manifest.buildLineage(newActiveUid, upHops = cur.upHops, downHops = cur.downHops)
        } else {
            LineagePayload()
        }
        val publishedUids = payload.models.map { it.uniqueId }.toSet()
        state.updateAndGet { stateAfterPublishFull(it, newActiveUid, publishedUids) }
        publisher.lineagePayloadChanged(payload)
    }

    companion object {
        const val DEFAULT_UP_HOPS = 3
        const val DEFAULT_DOWN_HOPS = 3

        /**
         * Minimum interval between progressive `columnLineageDone=false`
         * republishes during a streaming column-lineage trace. Each
         * republish triggers a React re-render of the DAG; too frequent
         * (per-edge) saturates JCEF's event loop, too rare defeats the
         * "live progress" UX point. 500ms is the empirical sweet spot
         * for the iCHEF-sized project: edges typically arrive in bursts
         * and the user perceives ~2Hz updates as continuous.
         */
        const val STREAM_PUBLISH_INTERVAL_MS = 500L

        @JvmField
        val TOPIC: Topic<LineageInfoListener> = Topic.create(
            "intellij-dbtree LineageInfoTopic",
            LineageInfoListener::class.java,
        )
    }
}
