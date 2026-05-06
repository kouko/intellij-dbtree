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
