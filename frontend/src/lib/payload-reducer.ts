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
