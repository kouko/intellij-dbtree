import { describe, it, expect } from "vitest";
import { layoutModelGraph } from "./layout-elk";

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
