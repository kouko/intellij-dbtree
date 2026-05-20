import type { ColumnEdge, LineagePayload } from "../types";

/**
 * Wire-format companion to Kotlin's `columnEdgesAppended` listener.
 *
 * The Kotlin side streams column-lineage edges in deltas (one push per
 * 500ms flush, plus a final push with done=true) rather than re-sending
 * the whole payload, so the JCEF bridge isn't asked to JSON-parse a
 * multi-megabyte script source on every flush and React's memo cascade
 * stays quiet on the topology fields. See LineageInfoService.onColumnClicked
 * for the producer side.
 */
export interface ColumnEdgesDelta {
  append_edges: ColumnEdge[];
  column_lineage_done: boolean;
  /**
   * Null clears any prior warning (the trace completed cleanly);
   * non-null replaces it. Wire-format choice: the Kotlin emitter always
   * sends this field explicitly so the React side never has to guess
   * "is this missing because nothing changed or because it cleared".
   */
  warning: string | null;
}

/**
 * Fold a streaming column-edges delta into the current payload.
 *
 * Topology fields (`models`, `model_edges`, `selected`) are passed
 * through by reference. That's load-bearing: App.tsx's memo cascade
 * keys layout and node-rendering work on these references, so any
 * structural copy here would re-fire ELK and the React-Flow node
 * pipeline on every 500ms flush — the exact freeze this reducer is
 * here to avoid.
 *
 * Only `column_edges` gets a fresh array (so memos keyed on it pick up
 * the new trace edges), and `column_lineage_done` / `warning` are
 * overwritten verbatim from the delta.
 */
export function applyColumnEdgesDelta(
  prev: LineagePayload,
  delta: ColumnEdgesDelta,
): LineagePayload {
  return {
    ...prev,
    column_edges: prev.column_edges.concat(delta.append_edges),
    column_lineage_done: delta.column_lineage_done,
    warning: delta.warning ?? undefined,
  };
}

/**
 * Merge a fresh full payload from Kotlin onto the current one, carrying
 * over Python-sidecar-extracted columns for any model that appears in
 * both. Used by App.tsx's `window.setLineageInfo` to defend against the
 * column-loss-on-republish UX bug.
 *
 * Why this exists:
 *   Kotlin's `ManifestService.describe()` populates `columns` from dbt
 *   yml docs + catalog.json — both of which are commonly empty for
 *   intermediate models in real projects. The Python sidecar then patches
 *   in sqlglot-derived columns asynchronously via `modelColumnsUpdated`.
 *   Any subsequent full payload (model navigation, hop change, manifest
 *   reload triggered by `dbt compile`, column-click republish) shipped
 *   the manifest-derived empty columns again, wiping the sidecar
 *   patches. The frontend then re-prefetched everything, and if any
 *   single publish silently dropped in the Kotlin → JCEF → React chain
 *   (EDT contention, page-ready races, etc.), the card stayed on
 *   "Parsing SQL…" indefinitely with no retry path.
 *
 * Merge policy:
 *   - If `next` already has non-empty columns for a uid → use next's
 *     (and keep the same model object reference, so downstream memos
 *     are stable).
 *   - Else if `prev` had non-empty columns for the same uid → carry
 *     those over onto next's model.
 *   - Else → use next's empty list as-is; the frontend's prefetch effect
 *     will request them via the sidecar.
 *
 * Models present only in prev are dropped (the new payload defines the
 * visible DAG; previously-visible models that fell outside the new hop
 * window should not linger).
 */
export function mergePayloadPreservingColumns(
  prev: LineagePayload,
  next: LineagePayload,
): LineagePayload {
  const prevColumnsByUid = new Map<string, LineagePayload["models"][number]["columns"]>();
  for (const m of prev.models) {
    if (m.columns.length > 0) prevColumnsByUid.set(m.unique_id, m.columns);
  }
  if (prevColumnsByUid.size === 0) return next;
  const mergedModels = next.models.map((m) => {
    if (m.columns.length > 0) return m;
    const carried = prevColumnsByUid.get(m.unique_id);
    return carried ? { ...m, columns: carried } : m;
  });
  return { ...next, models: mergedModels };
}
