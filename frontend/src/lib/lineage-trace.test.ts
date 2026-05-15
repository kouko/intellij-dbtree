import { describe, expect, it } from "vitest";
import type { ColumnEdge, ModelEdge } from "../types";
import {
  buildColumnLineageTrace,
  buildColumnTraceEdgePairs,
  buildModelTrace,
  edgeKey,
  isEdgeOnModelTreePath,
} from "./lineage-trace";

// Shorthand constructors keep the test fixtures readable.
const me = (s: string, t: string): ModelEdge => ({
  source_unique_id: s,
  target_unique_id: t,
});
const ce = (s: string, sc: string, t: string, tc: string): ColumnEdge => ({
  source_unique_id: s,
  source_column: sc,
  target_unique_id: t,
  target_column: tc,
});

describe("edgeKey", () => {
  it("encodes endpoints + columns into a stable string", () => {
    expect(edgeKey(ce("a", "x", "b", "y"))).toBe("a|x->b|y");
  });

  it("collisions: distinct edges produce distinct keys", () => {
    expect(edgeKey(ce("a", "x", "b", "y"))).not.toBe(edgeKey(ce("a", "x", "c", "y")));
    expect(edgeKey(ce("a", "x", "b", "y"))).not.toBe(edgeKey(ce("a", "z", "b", "y")));
  });
});

describe("buildModelTrace", () => {
  // Linear: a → b → c → d
  const linear = [me("a", "b"), me("b", "c"), me("c", "d")];

  it("returns empty trace when no model is selected", () => {
    const t = buildModelTrace(null, linear);
    expect(t.ancestors.size).toBe(0);
    expect(t.descendants.size).toBe(0);
    expect(t.all.size).toBe(0);
  });

  it("seed at one end: ancestors fully populated, descendants empty", () => {
    const t = buildModelTrace("d", linear);
    expect(t.ancestors).toEqual(new Set(["a", "b", "c"]));
    expect(t.descendants.size).toBe(0);
    expect(t.all).toEqual(new Set(["a", "b", "c", "d"]));
  });

  it("seed in middle: ancestors and descendants split correctly", () => {
    const t = buildModelTrace("b", linear);
    expect(t.ancestors).toEqual(new Set(["a"]));
    expect(t.descendants).toEqual(new Set(["c", "d"]));
    expect(t.all).toEqual(new Set(["a", "b", "c", "d"]));
  });

  it("seed excluded from its own ancestors and descendants", () => {
    const t = buildModelTrace("b", linear);
    expect(t.ancestors.has("b")).toBe(false);
    expect(t.descendants.has("b")).toBe(false);
    expect(t.all.has("b")).toBe(true);
  });

  // Diamond: orders → customers → ccm, orders → ccm
  // The key regression: BFS from `customers` must still reach orders
  // (upstream) and ccm (downstream), each in their own set, even though
  // a direct orders → ccm edge bypasses the seed.
  const diamond = [
    me("orders", "customers"),
    me("customers", "ccm"),
    me("orders", "ccm"),
  ];

  it("diamond: ancestors and descendants stay disjoint", () => {
    const t = buildModelTrace("customers", diamond);
    expect(t.ancestors).toEqual(new Set(["orders"]));
    expect(t.descendants).toEqual(new Set(["ccm"]));
    // The skip edge orders → ccm does NOT make ccm an ancestor or
    // orders a descendant. They stay strictly on their own side.
    expect(t.descendants.has("orders")).toBe(false);
    expect(t.ancestors.has("ccm")).toBe(false);
  });

  it("does not over-collect via bidirectional bouncing (regression)", () => {
    // Earlier implementation walked up to a parent and then down again
    // into the parent's *other* children, flooding the connected component.
    // With seed=customers in this graph, "siblings of customers under orders"
    // (i.e. ccm via orders→ccm) must NOT land in ancestors.
    //
    //   orders ─┬─→ customers
    //           └─→ ccm
    const graph = [me("orders", "customers"), me("orders", "ccm")];
    const t = buildModelTrace("customers", graph);
    expect(t.ancestors).toEqual(new Set(["orders"]));
    expect(t.descendants.size).toBe(0);
    expect(t.ancestors.has("ccm")).toBe(false);
  });

  it("isolated seed has empty ancestors and descendants", () => {
    const t = buildModelTrace("isolated", [me("x", "y")]);
    expect(t.ancestors.size).toBe(0);
    expect(t.descendants.size).toBe(0);
    expect(t.all).toEqual(new Set(["isolated"]));
  });
});

describe("isEdgeOnModelTreePath (skip-edge highlight rule)", () => {
  // Diamond again, seed=customers.
  const diamond = [
    me("orders", "customers"),
    me("customers", "ccm"),
    me("orders", "ccm"),
  ];
  const trace = buildModelTrace("customers", diamond);

  it("highlights upstream chain edge (ancestor → seed)", () => {
    expect(isEdgeOnModelTreePath("orders", "customers", "customers", trace)).toBe(true);
  });

  it("highlights downstream chain edge (seed → descendant)", () => {
    expect(isEdgeOnModelTreePath("customers", "ccm", "customers", trace)).toBe(true);
  });

  it("does NOT highlight skip edge bypassing the seed (regression)", () => {
    // orders is an ancestor, ccm is a descendant. The direct orders→ccm
    // edge bypasses customers — it's not on customers' lineage tree.
    expect(isEdgeOnModelTreePath("orders", "ccm", "customers", trace)).toBe(false);
  });

  it("returns false when no model is selected", () => {
    expect(isEdgeOnModelTreePath("orders", "customers", null, trace)).toBe(false);
  });

  it("returns false for an edge with neither endpoint in the trace", () => {
    expect(isEdgeOnModelTreePath("foo", "bar", "customers", trace)).toBe(false);
  });

  it("highlights deeper-chain downstream edge (descendant → descendant)", () => {
    // a → b → c → d, seed=b
    //   b is seed; c, d are descendants.
    //   c → d should highlight (downstream chain edge that doesn't touch the seed).
    const linear = [me("a", "b"), me("b", "c"), me("c", "d")];
    const t = buildModelTrace("b", linear);
    expect(isEdgeOnModelTreePath("c", "d", "b", t)).toBe(true);
  });

  it("highlights deeper-chain upstream edge (ancestor → ancestor)", () => {
    // a → b → c → d, seed=d
    //   a, b, c are ancestors; d is seed.
    //   a → b should highlight.
    const linear = [me("a", "b"), me("b", "c"), me("c", "d")];
    const t = buildModelTrace("d", linear);
    expect(isEdgeOnModelTreePath("a", "b", "d", t)).toBe(true);
  });
});

describe("buildColumnLineageTrace", () => {
  // a.x → b.x → c.x  AND  a.y → b.y → c.x  (b.x and b.y both feed c.x)
  const edges = [
    ce("a", "x", "b", "x"),
    ce("b", "x", "c", "x"),
    ce("a", "y", "b", "y"),
    ce("b", "y", "c", "x"),
  ];

  it("returns empty trace when no column is selected", () => {
    const t = buildColumnLineageTrace(null, edges);
    expect(t.columns.size).toBe(0);
    expect(t.edges.size).toBe(0);
    expect(t.models.size).toBe(0);
  });

  it("walks both directions from the seed and records visited columns", () => {
    // Seed b.x: walks down to c.x, walks up to a.x.
    // a.y / b.y / c.x-via-b.y are NOT on this column's path.
    const t = buildColumnLineageTrace({ unique_id: "b", column: "x" }, edges);
    // Visited (model, column) pairs:
    expect(t.columns.get("a")).toEqual(new Set(["x"]));
    expect(t.columns.get("b")).toEqual(new Set(["x"]));
    expect(t.columns.get("c")).toEqual(new Set(["x"]));
    expect(t.columns.has("d")).toBe(false);
    // Models are the union of visited model uids
    expect(t.models).toEqual(new Set(["a", "b", "c"]));
  });

  it("records edge keys for every column edge traversed", () => {
    const t = buildColumnLineageTrace({ unique_id: "b", column: "x" }, edges);
    expect(t.edges.has(edgeKey(ce("a", "x", "b", "x")))).toBe(true); // upstream
    expect(t.edges.has(edgeKey(ce("b", "x", "c", "x")))).toBe(true); // downstream
    // Edges on the b.y column are NOT included
    expect(t.edges.has(edgeKey(ce("a", "y", "b", "y")))).toBe(false);
    expect(t.edges.has(edgeKey(ce("b", "y", "c", "x")))).toBe(false);
  });

  it("downstream walk to a column with multiple inbound edges follows only the seed's edge", () => {
    // c.x has TWO inbound edges (b.x→c.x and b.y→c.x). Seeding b.x should
    // only traverse the b.x→c.x edge — NOT the unrelated b.y→c.x edge that
    // happens to point at the same target.
    const t = buildColumnLineageTrace({ unique_id: "b", column: "x" }, edges);
    expect(t.columns.get("b")).toEqual(new Set(["x"]));
    expect(t.columns.get("b")?.has("y")).toBe(false);
  });

  it("seed column is always in the trace, even when isolated", () => {
    const t = buildColumnLineageTrace(
      { unique_id: "lonely", column: "id" },
      [ce("a", "x", "b", "x")],
    );
    expect(t.models).toEqual(new Set(["lonely"]));
    expect(t.columns.get("lonely")).toEqual(new Set(["id"]));
  });

  it("handles diamond where downstream rejoins upstream chain (BFS doesn't loop)", () => {
    // a.x → b.x → d.x
    // a.x → c.x → d.x
    // Seed a.x: goes down through both branches; b.x and c.x both reach d.x.
    // No infinite loop, all four columns visited.
    const diamondEdges = [
      ce("a", "x", "b", "x"),
      ce("a", "x", "c", "x"),
      ce("b", "x", "d", "x"),
      ce("c", "x", "d", "x"),
    ];
    const t = buildColumnLineageTrace({ unique_id: "a", column: "x" }, diamondEdges);
    expect(t.models).toEqual(new Set(["a", "b", "c", "d"]));
    expect(t.edges.size).toBe(4);
  });
});

describe("buildColumnLineageTrace + modelEdges (source augmentation)", () => {
  // Base column edges: a.x → b.x → c.x
  const columnEdges = [ce("a", "x", "b", "x"), ce("b", "x", "c", "x")];

  it("adds source uid when its target is already in trace.models", () => {
    const modelEdges = [me("source.proj.db.raw", "c")];
    const t = buildColumnLineageTrace({ unique_id: "b", column: "x" }, columnEdges, modelEdges);
    // a, b, c are in trace via column BFS; source.proj.db.raw targets c → should be added
    expect(t.models.has("source.proj.db.raw")).toBe(true);
  });

  it("does NOT add source uid when its target is NOT in trace.models", () => {
    // d is not visited by the BFS, so source → d should not pull source in
    const modelEdges = [me("source.proj.db.raw", "d")];
    const t = buildColumnLineageTrace({ unique_id: "b", column: "x" }, columnEdges, modelEdges);
    expect(t.models.has("source.proj.db.raw")).toBe(false);
  });

  it("backward compat: calling without 3rd arg works (no augmentation, no crash)", () => {
    const t = buildColumnLineageTrace({ unique_id: "b", column: "x" }, columnEdges);
    expect(t.models).toEqual(new Set(["a", "b", "c"]));
  });

  it("adds multiple sources that all target the same in-trace model", () => {
    const modelEdges = [
      me("source.proj.db.raw1", "c"),
      me("source.proj.db.raw2", "c"),
    ];
    const t = buildColumnLineageTrace({ unique_id: "b", column: "x" }, columnEdges, modelEdges);
    expect(t.models.has("source.proj.db.raw1")).toBe(true);
    expect(t.models.has("source.proj.db.raw2")).toBe(true);
  });

  it("does NOT add non-source.* uids even when both endpoints are present", () => {
    // model.proj.something → c is a model→model edge, not a source edge;
    // the augmentation pass is source-specific and must skip it.
    const modelEdges = [me("model.proj.something", "c")];
    const t = buildColumnLineageTrace({ unique_id: "b", column: "x" }, columnEdges, modelEdges);
    expect(t.models.has("model.proj.something")).toBe(false);
  });
});

describe("buildColumnTraceEdgePairs + modelEdges + traceModels (source→model promotion)", () => {
  const columnEdges = [ce("a", "x", "b", "x")];
  const traceEdges = new Set([edgeKey(ce("a", "x", "b", "x"))]);

  it("adds source→model pair when both endpoints are in traceModels", () => {
    const modelEdges = [me("source.proj.db.src", "b")];
    const traceModels = new Set(["source.proj.db.src", "a", "b"]);
    const pairs = buildColumnTraceEdgePairs(
      { unique_id: "a", column: "x" },
      columnEdges,
      traceEdges,
      modelEdges,
      traceModels,
    );
    expect(pairs.has("source.proj.db.src|b")).toBe(true);
    // column edge pair is still present
    expect(pairs.has("a|b")).toBe(true);
  });

  it("does NOT add source→model pair when source is NOT in traceModels", () => {
    const modelEdges = [me("source.proj.db.src", "b")];
    // source uid intentionally absent from traceModels
    const traceModels = new Set(["a", "b"]);
    const pairs = buildColumnTraceEdgePairs(
      { unique_id: "a", column: "x" },
      columnEdges,
      traceEdges,
      modelEdges,
      traceModels,
    );
    expect(pairs.has("source.proj.db.src|b")).toBe(false);
  });

  it("does NOT add source→model pair when target is NOT in traceModels (defensive)", () => {
    const modelEdges = [me("source.proj.db.src", "b")];
    // target b is intentionally absent from traceModels
    const traceModels = new Set(["source.proj.db.src", "a"]);
    const pairs = buildColumnTraceEdgePairs(
      { unique_id: "a", column: "x" },
      columnEdges,
      traceEdges,
      modelEdges,
      traceModels,
    );
    expect(pairs.has("source.proj.db.src|b")).toBe(false);
  });

  it("backward compat: 3-arg call works (no source augmentation)", () => {
    const pairs = buildColumnTraceEdgePairs(
      { unique_id: "a", column: "x" },
      columnEdges,
      traceEdges,
    );
    expect(pairs).toEqual(new Set(["a|b"]));
  });
});

describe("buildColumnTraceEdgePairs", () => {
  it("returns empty when no column is selected", () => {
    expect(
      buildColumnTraceEdgePairs(null, [ce("a", "x", "b", "y")], new Set()),
    ).toEqual(new Set());
  });

  it("emits one (source|target) pair per traced column edge", () => {
    const edges = [
      ce("a", "x", "b", "x"),
      ce("b", "x", "c", "x"),
    ];
    const traceEdges = new Set([
      edgeKey(ce("a", "x", "b", "x")),
      edgeKey(ce("b", "x", "c", "x")),
    ]);
    const pairs = buildColumnTraceEdgePairs(
      { unique_id: "b", column: "x" },
      edges,
      traceEdges,
    );
    expect(pairs).toEqual(new Set(["a|b", "b|c"]));
  });

  it("dedupes when two columns happen to share the same model→model arrow", () => {
    // Two distinct column edges between a and b: a.x→b.x and a.y→b.y.
    // Both contribute the same "a|b" pair — should appear once.
    const edges = [ce("a", "x", "b", "x"), ce("a", "y", "b", "y")];
    const traceEdges = new Set([
      edgeKey(ce("a", "x", "b", "x")),
      edgeKey(ce("a", "y", "b", "y")),
    ]);
    const pairs = buildColumnTraceEdgePairs(
      { unique_id: "a", column: "x" },
      edges,
      traceEdges,
    );
    expect(pairs).toEqual(new Set(["a|b"]));
  });

  it("does not include edges that aren't in the trace", () => {
    const edges = [
      ce("a", "x", "b", "x"),
      ce("c", "x", "d", "x"),
    ];
    // Only the first edge is in the trace
    const traceEdges = new Set([edgeKey(ce("a", "x", "b", "x"))]);
    const pairs = buildColumnTraceEdgePairs(
      { unique_id: "a", column: "x" },
      edges,
      traceEdges,
    );
    expect(pairs).toEqual(new Set(["a|b"]));
    expect(pairs.has("c|d")).toBe(false);
  });
});

// Pins the algorithmic complexity of the two BFS builders: on a graph
// the size of a real medium-large dbt project (~1000 models, ~6k model
// edges, ~60k column edges), a single trace must finish well under the
// JCEF stream-publish interval (500ms). The earlier O(V×E) implementation
// took ~170ms PER CALL on this fixture and was re-invoked on every
// streaming publish during a column trace, cascading the dbtree panel
// into a frozen state on the iCHEF project. New adjacency-map BFS runs in
// single-digit ms, so 50ms is comfortably above noise without being
// loose enough to let O(V×E) creep back in.
describe("buildColumnLineageTrace / buildModelTrace at scale (perf gate)", () => {
  function buildLargeGraph(layers: number, perLayer: number, fanout: number, cols: number) {
    const modelEdges: ModelEdge[] = [];
    const columnEdges: ColumnEdge[] = [];
    for (let L = 0; L < layers - 1; L++) {
      for (let n = 0; n < perLayer; n++) {
        const src = `m${L * perLayer + n}`;
        for (let k = 0; k < fanout; k++) {
          const dst = `m${(L + 1) * perLayer + ((n + k * 7) % perLayer)}`;
          modelEdges.push(me(src, dst));
          for (let c = 0; c < cols; c++) {
            columnEdges.push(ce(src, `c${c}`, dst, `c${c}`));
          }
        }
      }
    }
    return { modelEdges, columnEdges };
  }

  // Sized to match a medium-large dbt project: 1000 models, ~6k model
  // edges, ~60k column edges. Matches the iCHEF-dbt-pipeline observed
  // scale within ~20%.
  const { modelEdges, columnEdges } = buildLargeGraph(100, 10, 6, 10);

  it("buildColumnLineageTrace finishes under 50ms on a 1000-model graph", () => {
    const t0 = performance.now();
    const trace = buildColumnLineageTrace(
      { unique_id: "m0", column: "c0" },
      columnEdges,
      modelEdges,
    );
    const ms = performance.now() - t0;
    expect(trace.models.size).toBeGreaterThan(0);
    expect(ms).toBeLessThan(50);
  });

  it("buildModelTrace finishes under 30ms on a 1000-model graph", () => {
    const t0 = performance.now();
    const trace = buildModelTrace("m0", modelEdges);
    const ms = performance.now() - t0;
    expect(trace.all.size).toBeGreaterThan(0);
    expect(ms).toBeLessThan(30);
  });
});
