import ELK from "elkjs/lib/elk.bundled.js";
import type { Edge, Node } from "@xyflow/react";

export interface LayoutOptions {
  rankdir?: "LR" | "TB";
  nodeWidth: number;
  nodesepX: number;
  ranksepY: number;
  heights: Record<string, number>;
}

const elk = new ELK();

export async function layoutModelGraph(
  nodes: Node[],
  edges: Edge[],
  opts: LayoutOptions,
): Promise<Node[]> {
  if (nodes.length === 0) return [];

  const direction = (opts.rankdir ?? "LR") === "LR" ? "RIGHT" : "DOWN";
  const graph = {
    id: "root",
    layoutOptions: {
      "elk.algorithm": "layered",
      "elk.direction": direction,
      "elk.layered.spacing.nodeNodeBetweenLayers": String(opts.ranksepY),
      "elk.spacing.nodeNode": String(opts.nodesepX),
      "elk.layered.layerUnzipping.strategy": "ALTERNATING",
      "elk.layered.layerUnzipping.layerSplit": "2",
      "elk.layered.layerUnzipping.resetOnLongEdges": "true",
      "elk.layered.nodePlacement.strategy": "BRANDES_KOEPF",
      "elk.layered.thoroughness": "10",
    },
    children: nodes.map((n) => ({
      id: n.id,
      width: opts.nodeWidth,
      height: opts.heights[n.id] ?? 60,
    })),
    edges: edges.map((e) => ({
      id: e.id,
      sources: [e.source],
      targets: [e.target],
    })),
  };

  const result = await elk.layout(graph);
  const childById = new Map((result.children ?? []).map((c) => [c.id, c]));

  return nodes.map((n) => {
    const c = childById.get(n.id);
    const height = opts.heights[n.id] ?? 60;
    return {
      ...n,
      position: { x: c?.x ?? 0, y: c?.y ?? 0 },
      width: opts.nodeWidth,
      height,
    };
  });
}
