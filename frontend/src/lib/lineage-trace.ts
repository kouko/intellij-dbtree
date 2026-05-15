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
  modelEdges: readonly ModelEdge[] = [],
): ColumnLineageTrace {
  const trace: ColumnLineageTrace = {
    columns: new Map(),
    edges: new Set(),
    models: new Set(),
  };
  if (!selectedColumn) return trace;

  // O(V+E) BFS instead of O(V×E): bucket column edges by endpoint once
  // up-front, then look up neighbours from the bucket inside the BFS
  // loop. The earlier nested `for (const ce of columnEdges)` scan was
  // re-invoked on every streaming column-trace publish, snowballing into
  // a frozen JCEF panel on large dbt projects.
  const colKey = (uid: string, col: string) => `${uid}|${col}`;
  const upstreamByTarget = new Map<string, ColumnEdge[]>();
  const downstreamBySource = new Map<string, ColumnEdge[]>();
  for (const ce of columnEdges) {
    const tgt = colKey(ce.target_unique_id, ce.target_column);
    const src = colKey(ce.source_unique_id, ce.source_column);
    const inbound = upstreamByTarget.get(tgt);
    if (inbound) inbound.push(ce); else upstreamByTarget.set(tgt, [ce]);
    const outbound = downstreamBySource.get(src);
    if (outbound) outbound.push(ce); else downstreamBySource.set(src, [ce]);
  }

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

  // Index-based queues keep dequeue O(1). `queue.shift()` is O(n) and
  // becomes a second-order bottleneck on long traces.
  const walk = (
    direction: "upstream" | "downstream",
  ) => {
    const queue: SelectedColumn[] = [selectedColumn];
    let head = 0;
    const seen = new Set<string>([colKey(selectedColumn.unique_id, selectedColumn.column)]);
    const adj = direction === "upstream" ? upstreamByTarget : downstreamBySource;
    while (head < queue.length) {
      const cur = queue[head++];
      const edgesHere = adj.get(colKey(cur.unique_id, cur.column));
      if (!edgesHere) continue;
      for (const ce of edgesHere) {
        trace.edges.add(edgeKey(ce));
        const next: SelectedColumn = direction === "upstream"
          ? { unique_id: ce.source_unique_id, column: ce.source_column }
          : { unique_id: ce.target_unique_id, column: ce.target_column };
        const key = colKey(next.unique_id, next.column);
        if (!seen.has(key)) {
          seen.add(key);
          noteVisit(next.unique_id, next.column);
          queue.push(next);
        }
      }
    }
  };
  walk("upstream");
  walk("downstream");

  // Source-augmentation pass: dbt sources frequently lack column docs,
  // so sqlglot's lineage walker drops their `SELECT *` references as
  // Placeholder leaves and the column trace never reaches them — even
  // though the model graph (model_edges) clearly knows the dependency.
  // Pull every source uid that is a direct upstream of an already-
  // visited model into trace.models so source cards still highlight
  // when the trace passes through their downstream consumer.
  for (const me of modelEdges) {
    if (
      me.source_unique_id.startsWith("source.") &&
      trace.models.has(me.target_unique_id)
    ) {
      trace.models.add(me.source_unique_id);
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

  // Bucket once, walk in O(V+E). See buildColumnLineageTrace for the
  // rationale; same hot path was making column traces snowball.
  const parentsOf = new Map<string, string[]>();
  const childrenOf = new Map<string, string[]>();
  for (const me of modelEdges) {
    const ps = parentsOf.get(me.target_unique_id);
    if (ps) ps.push(me.source_unique_id);
    else parentsOf.set(me.target_unique_id, [me.source_unique_id]);
    const cs = childrenOf.get(me.source_unique_id);
    if (cs) cs.push(me.target_unique_id);
    else childrenOf.set(me.source_unique_id, [me.target_unique_id]);
  }

  // Upstream
  {
    const queue: string[] = [selectedModelUid];
    let head = 0;
    while (head < queue.length) {
      const cur = queue[head++];
      const parents = parentsOf.get(cur);
      if (!parents) continue;
      for (const parent of parents) {
        if (parent === selectedModelUid || ancestors.has(parent)) continue;
        ancestors.add(parent);
        queue.push(parent);
      }
    }
  }

  // Downstream
  {
    const queue: string[] = [selectedModelUid];
    let head = 0;
    while (head < queue.length) {
      const cur = queue[head++];
      const children = childrenOf.get(cur);
      if (!children) continue;
      for (const child of children) {
        if (child === selectedModelUid || descendants.has(child)) continue;
        descendants.add(child);
        queue.push(child);
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
  modelEdges: readonly ModelEdge[] = [],
  traceModels: ReadonlySet<string> = new Set(),
): Set<string> {
  const pairs = new Set<string>();
  if (!selectedColumn) return pairs;
  for (const ce of columnEdges) {
    if (traceEdges.has(edgeKey(ce))) {
      pairs.add(`${ce.source_unique_id}|${ce.target_unique_id}`);
    }
  }
  // Source-to-model model edges where the source was added to the
  // trace via the augmentation pass in buildColumnLineageTrace —
  // promote them so the source→model line lights up too, not just
  // the source card halo.
  for (const me of modelEdges) {
    if (
      me.source_unique_id.startsWith("source.") &&
      traceModels.has(me.source_unique_id) &&
      traceModels.has(me.target_unique_id)
    ) {
      pairs.add(`${me.source_unique_id}|${me.target_unique_id}`);
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
