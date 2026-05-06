/**
 * Pure lineage-trace logic shared by `App.tsx` and the test suite.
 *
 * Everything here is deterministic and side-effect-free: given the same
 * payload + selection, you get the same trace. That's why this lives
 * outside the React component — it's testable in isolation, and the
 * component's `useMemo` calls just delegate to these functions.
 *
 * Three concepts the rest of the app reads:
 *
 *  - **Column trace** (`buildColumnLineageTrace`): BFS upstream and
 *    downstream from a (model, column) seed over `column_edges`. Each
 *    walk runs in one direction so we don't accidentally hop across the
 *    seed and over-collect.
 *
 *  - **Model trace** (`buildModelTrace`): BFS upstream and downstream
 *    from a model uid over `model_edges`. Returned as
 *    `{ ancestors, descendants, all }` so the edge classifier can tell
 *    "edge in the upstream chain" from "edge in the downstream chain"
 *    from "skip edge that bypasses the seed entirely".
 *
 *  - **Edge classification** (`isEdgeOnModelTreePath`): given a model
 *    edge `(a, b)` and a model trace, decide whether it should be
 *    highlighted. The crucial nuance is rejecting *skip edges*: an
 *    ancestor connected directly to a descendant without going through
 *    the seed is NOT on the seed's lineage tree.
 */

import type { ColumnEdge, ModelEdge } from "../types";

export interface SelectedColumn {
  unique_id: string;
  column: string;
}

export interface ColumnLineageTrace {
  /** Map: model uid → set of columns highlighted in that model. */
  columns: Map<string, Set<string>>;
  /** Set of `edgeKey()`-encoded column_edges along the trace. */
  edges: Set<string>;
  /** Set of model uids touched by the trace (for card-level highlight). */
  models: Set<string>;
}

export interface ModelTrace {
  /** Strict ancestors — does NOT include the seed itself. */
  ancestors: Set<string>;
  /** Strict descendants — does NOT include the seed itself. */
  descendants: Set<string>;
  /** Convenience: ancestors ∪ {seed} ∪ descendants. */
  all: Set<string>;
}

export function edgeKey(ce: ColumnEdge): string {
  return `${ce.source_unique_id}|${ce.source_column}->${ce.target_unique_id}|${ce.target_column}`;
}

/**
 * BFS upstream + downstream from a (model, column) seed.
 *
 * The two walks run independently with their own `seen` sets, so a
 * column reached upstream can also be reached downstream through a
 * different path without short-circuiting the BFS — this matters for
 * diamond-shaped column graphs.
 */
export function buildColumnLineageTrace(
  selectedColumn: SelectedColumn | null,
  columnEdges: readonly ColumnEdge[],
): ColumnLineageTrace {
  const trace: ColumnLineageTrace = {
    columns: new Map(),
    edges: new Set(),
    models: new Set(),
  };
  if (!selectedColumn) return trace;

  const noteVisit = (uniqueId: string, column: string) => {
    let cols = trace.columns.get(uniqueId);
    if (!cols) {
      cols = new Set();
      trace.columns.set(uniqueId, cols);
    }
    cols.add(column);
    trace.models.add(uniqueId);
  };
  noteVisit(selectedColumn.unique_id, selectedColumn.column);

  // Upstream walk: edges where target == cur, jump to source.
  {
    const queue: SelectedColumn[] = [selectedColumn];
    const seen = new Set<string>([
      `${selectedColumn.unique_id}|${selectedColumn.column}`,
    ]);
    while (queue.length > 0) {
      const cur = queue.shift()!;
      for (const ce of columnEdges) {
        if (ce.target_unique_id === cur.unique_id && ce.target_column === cur.column) {
          trace.edges.add(edgeKey(ce));
          const next: SelectedColumn = {
            unique_id: ce.source_unique_id,
            column: ce.source_column,
          };
          const key = `${next.unique_id}|${next.column}`;
          if (!seen.has(key)) {
            seen.add(key);
            noteVisit(next.unique_id, next.column);
            queue.push(next);
          }
        }
      }
    }
  }

  // Downstream walk: edges where source == cur, jump to target.
  {
    const queue: SelectedColumn[] = [selectedColumn];
    const seen = new Set<string>([
      `${selectedColumn.unique_id}|${selectedColumn.column}`,
    ]);
    while (queue.length > 0) {
      const cur = queue.shift()!;
      for (const ce of columnEdges) {
        if (ce.source_unique_id === cur.unique_id && ce.source_column === cur.column) {
          trace.edges.add(edgeKey(ce));
          const next: SelectedColumn = {
            unique_id: ce.target_unique_id,
            column: ce.target_column,
          };
          const key = `${next.unique_id}|${next.column}`;
          if (!seen.has(key)) {
            seen.add(key);
            noteVisit(next.unique_id, next.column);
            queue.push(next);
          }
        }
      }
    }
  }

  return trace;
}

/**
 * BFS upstream + downstream from a model uid.
 *
 * Each walk is strictly directional: the upstream BFS only follows
 * `target_unique_id == cur` edges (towards source), the downstream BFS
 * only follows `source_unique_id == cur` edges (towards target). This
 * was a deliberate fix for an earlier bidirectional implementation that
 * over-collected — walking up to a parent and then down again into the
 * parent's *other* children flooded the connected component.
 *
 * The seed itself is excluded from both `ancestors` and `descendants`
 * (it's only in `all`).
 */
export function buildModelTrace(
  selectedModelUid: string | null,
  modelEdges: readonly ModelEdge[],
): ModelTrace {
  const ancestors = new Set<string>();
  const descendants = new Set<string>();
  if (!selectedModelUid) {
    return { ancestors, descendants, all: new Set() };
  }

  // Upstream
  {
    const queue: string[] = [selectedModelUid];
    while (queue.length > 0) {
      const cur = queue.shift()!;
      for (const me of modelEdges) {
        if (
          me.target_unique_id === cur &&
          me.source_unique_id !== selectedModelUid &&
          !ancestors.has(me.source_unique_id)
        ) {
          ancestors.add(me.source_unique_id);
          queue.push(me.source_unique_id);
        }
      }
    }
  }

  // Downstream
  {
    const queue: string[] = [selectedModelUid];
    while (queue.length > 0) {
      const cur = queue.shift()!;
      for (const me of modelEdges) {
        if (
          me.source_unique_id === cur &&
          me.target_unique_id !== selectedModelUid &&
          !descendants.has(me.target_unique_id)
        ) {
          descendants.add(me.target_unique_id);
          queue.push(me.target_unique_id);
        }
      }
    }
  }

  return {
    ancestors,
    descendants,
    all: new Set([selectedModelUid, ...ancestors, ...descendants]),
  };
}

/**
 * Collapse the column trace's edge set to "{src}|{tgt}" model pairs.
 * The DAG edge highlighter compares against this set so column-level
 * highlighting only colors edges that the chosen column actually
 * traverses (not every edge between two models that happen to be in
 * the trace).
 */
export function buildColumnTraceEdgePairs(
  selectedColumn: SelectedColumn | null,
  columnEdges: readonly ColumnEdge[],
  traceEdges: ReadonlySet<string>,
): Set<string> {
  const pairs = new Set<string>();
  if (!selectedColumn) return pairs;
  for (const ce of columnEdges) {
    if (traceEdges.has(edgeKey(ce))) {
      pairs.add(`${ce.source_unique_id}|${ce.target_unique_id}`);
    }
  }
  return pairs;
}

/**
 * True iff edge `(a → b)` should be highlighted given a model trace.
 *
 *   - downstream edge: `a == seed` or `a` is a descendant, AND `b` is a descendant
 *   - upstream edge:   `a` is an ancestor, AND (`b == seed` or `b` is an ancestor)
 *
 * **Skip-edge rule (the test we keep coming back to):** an edge from
 * an ancestor straight to a descendant — bypassing the seed — must
 * NOT highlight. Example with seed=customers and the diamond
 * orders→customers→customer_combined_metrics + orders→customer_combined_metrics:
 * the lateral `orders → customer_combined_metrics` edge is rejected
 * here because `orders ∈ ancestors` and `customer_combined_metrics ∈ descendants`,
 * so neither the downstream nor the upstream rule fires.
 */
export function isEdgeOnModelTreePath(
  a: string,
  b: string,
  selectedModelUid: string | null,
  modelTrace: ModelTrace,
): boolean {
  if (!selectedModelUid) return false;
  const aSeed = a === selectedModelUid;
  const bSeed = b === selectedModelUid;
  const downstreamEdge =
    (aSeed || modelTrace.descendants.has(a)) && modelTrace.descendants.has(b);
  const upstreamEdge =
    modelTrace.ancestors.has(a) && (bSeed || modelTrace.ancestors.has(b));
  return downstreamEdge || upstreamEdge;
}
