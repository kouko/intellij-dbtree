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
