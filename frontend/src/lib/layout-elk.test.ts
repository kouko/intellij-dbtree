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
