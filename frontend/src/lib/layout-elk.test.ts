import { describe, it, expect } from "vitest";
import type { Node, Edge } from "@xyflow/react";
import { layoutModelGraph } from "./layout-elk";

function makeNode(id: string): Node {
  return { id, type: "default", position: { x: 0, y: 0 }, data: {} };
}

function makeEdge(id: string, source: string, target: string): Edge {
  return { id, source, target };
}

describe("layoutModelGraph (elk)", () => {
  it("returns empty array for empty input", async () => {
    const result = await layoutModelGraph([], [], {
      nodeWidth: 200,
      nodesepX: 60,
      ranksepY: 100,
      heights: {},
    });
    expect(result).toEqual([]);
  });
});

describe("layoutModelGraph (elk) — linear chain", () => {
  it("places nodes in monotonic increasing x for LR direction", async () => {
    const ids = ["a", "b", "c", "d", "e"];
    const nodes = ids.map(makeNode);
    const edges = [
      makeEdge("ab", "a", "b"),
      makeEdge("bc", "b", "c"),
      makeEdge("cd", "c", "d"),
      makeEdge("de", "d", "e"),
    ];
    const heights = Object.fromEntries(ids.map((id) => [id, 60]));

    const result = await layoutModelGraph(nodes, edges, {
      rankdir: "LR",
      nodeWidth: 200,
      nodesepX: 60,
      ranksepY: 100,
      heights,
    });

    const byId = new Map(result.map((r) => [r.id, r.position.x]));
    expect(byId.get("a")! < byId.get("b")!).toBe(true);
    expect(byId.get("b")! < byId.get("c")!).toBe(true);
    expect(byId.get("c")! < byId.get("d")!).toBe(true);
    expect(byId.get("d")! < byId.get("e")!).toBe(true);
  });
});

describe("layoutModelGraph (elk) — variable heights", () => {
  it("does not overlap two siblings with different heights in same layer", async () => {
    const nodes = [makeNode("s1"), makeNode("s2"), makeNode("s3"), makeNode("sink")];
    const edges = [
      makeEdge("e1", "s1", "sink"),
      makeEdge("e2", "s2", "sink"),
      makeEdge("e3", "s3", "sink"),
    ];
    const heights = { s1: 60, s2: 200, s3: 60, sink: 60 };

    const result = await layoutModelGraph(nodes, edges, {
      rankdir: "LR",
      nodeWidth: 200,
      nodesepX: 60,
      ranksepY: 100,
      heights,
    });

    // With layerUnzipping=ALTERNATING the three siblings may spread across
    // multiple sub-columns. Non-overlap only needs to hold *within* each x.
    const buckets = new Map<number, Array<{ top: number; bottom: number }>>();
    for (const r of result) {
      if (r.id === "sink") continue;
      const x = Math.round(r.position.x);
      const h = heights[r.id as keyof typeof heights];
      const top = r.position.y;
      if (!buckets.has(x)) buckets.set(x, []);
      buckets.get(x)!.push({ top, bottom: top + h });
    }

    for (const intervals of buckets.values()) {
      intervals.sort((a, b) => a.top - b.top);
      for (let i = 0; i < intervals.length - 1; i++) {
        expect(intervals[i].bottom).toBeLessThanOrEqual(intervals[i + 1].top);
      }
    }
  });
});

describe("layoutModelGraph (elk) — hub case", () => {
  it.todo(
    "uses layerUnzipping to spread 30 fan-in siblings across multiple sub-columns",
  );
});
