package dev.kouko.intellijdbtree.lineage

/**
 * Policy: given an incoming active model uid and the set of uids in the
 * currently-published DAG, decide whether the editor focus change should
 * trigger a lightweight selection update or a full DAG rebuild.
 *
 * **In DAG → SelectionOnly.** The frontend only re-paints the selected
 * model card. No re-layout, no fitView. This is what makes "click a node
 * inside the DAG, IDE opens the file, layout doesn't snap" possible — the
 * dbt Power User UX.
 *
 * **Outside DAG → Rebuild.** The user opened a model that isn't on the
 * current canvas, so we recenter the DAG around it.
 */
internal sealed interface FocusDecision {
    val uid: String

    data class SelectionOnly(override val uid: String) : FocusDecision
    data class Rebuild(override val uid: String) : FocusDecision
}

internal fun decideFocusEvent(uid: String, publishedUids: Set<String>): FocusDecision =
    if (uid in publishedUids) FocusDecision.SelectionOnly(uid) else FocusDecision.Rebuild(uid)

/**
 * Policy: a pooled task should drop its result if the service's epoch has
 * advanced since the task was dispatched. This is how we suppress
 * out-of-order publishes when slow I/O (manifest cold load, Python sidecar
 * ~1s) makes a late task race a fresher user intent.
 *
 * The contract is symmetric: monotonic epoch comparison only. Any other
 * state difference (active uid, hops) is irrelevant — a task captures its
 * epoch at dispatch and must lose if a newer entry-point bumped it since.
 */
internal fun isSuperseded(myEpoch: Long, current: LineageInfoService.State): Boolean =
    myEpoch != current.epoch

/**
 * Policy: a per-model column patch (sidecar's `listColumnsViaSidecar`
 * emitting a `modelColumnsUpdated` event) should only publish when the
 * model is still in the active payload. We deliberately do NOT consult
 * epoch — column requests are independent of payload-changing events,
 * and gating them on epoch would let any unrelated event (column click,
 * hop change) kill in-flight prefetches and leave cards stuck on
 * "Parsing SQL…" indefinitely.
 *
 * The check exists only to skip the IPC for models that were dropped
 * from the payload while sqlglot was running; `applyModelColumns` would
 * be a no-op on the frontend anyway.
 */
internal fun shouldPublishModelColumns(
    modelUid: String,
    current: LineageInfoService.State,
): Boolean = modelUid in current.publishedUids

/**
 * Policy: state transition after a topology-publishing event
 * (`publishFull`). Both `activeUid` (drifting selection seed used by
 * setHops / refresh) and `centerUid` (anchored seed used by column
 * trace) jump to the newly-focused model — a fresh publishFull is the
 * one moment they're both in sync.
 */
internal fun stateAfterPublishFull(
    state: LineageInfoService.State,
    newActiveUid: String?,
    publishedUids: Set<String>,
): LineageInfoService.State = state.copy(
    activeUid = newActiveUid,
    centerUid = newActiveUid,
    publishedUids = publishedUids,
)

/**
 * Policy: state transition after a SelectionOnly editor focus change
 * (clicking a card already in the published DAG). Only `activeUid`
 * drifts; `centerUid` MUST stay anchored to the most recent
 * publishFull seed so the column-lineage trace doesn't churn topology
 * across consecutive in-view clicks.
 */
internal fun stateAfterSelectionOnly(
    state: LineageInfoService.State,
    uid: String,
): LineageInfoService.State = state.copy(activeUid = uid)

/**
 * Translate a [ManifestService.RefreshResult] into the user-facing message
 * the toolbar shows when the lineage panel can't render a graph. Returns
 * null on success — the caller should publish an actual lineage payload
 * in that case.
 *
 * Wording prioritizes "what should I do next?" over "what went wrong":
 * - NoDbtProject → tell them what marker file we're looking for
 * - NoManifest   → tell them which dbt command produces it (the most
 *                  common cause is "I haven't run dbt parse yet")
 * - ParseError   → point at idea.log because the cause is usually a dbt
 *                  internal error or a bad manifest version
 */
internal fun manifestStatusWarning(result: ManifestService.RefreshResult): String? = when (result) {
    is ManifestService.RefreshResult.Ok -> null
    is ManifestService.RefreshResult.NoDbtProject ->
        "No dbt project found in this IntelliJ project. Open a folder containing dbt_project.yml."
    is ManifestService.RefreshResult.NoManifest ->
        "manifest.json not found at ${result.path}. " +
            "Run `dbt parse` (or `dbt run` / `dbt compile`) to generate it."
    is ManifestService.RefreshResult.ParseError ->
        "Failed to parse ${result.path.fileName}: " +
            "${result.cause.message ?: result.cause::class.simpleName}. " +
            "See idea.log for details."
}
