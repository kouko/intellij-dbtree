import dagre from "dagre";
import type { Edge, Node } from "@xyflow/react";
import type { LayoutResult } from "./layout-elk";

/** Run dagre on the model-level DAG and return positioned nodes. */
export interface LayoutOptions {
  rankdir?: "LR" | "TB";
  nodeWidth: number;
  /** Gap between adjacent nodes within the same layer. For LR this is
   *  vertical, for TB horizontal. */
  nodeSpacing: number;
  /** Gap between adjacent layers. For LR this is horizontal, for TB
   *  vertical. */
  layerSpacing: number;
  /** Total rendered height per node uniqueId. */
  heights: Record<string, number>;
}

export async function layoutModelGraph(
  nodes: Node[],
  edges: Edge[],
  opts: LayoutOptions,
): Promise<LayoutResult> {
  const g = new dagre.graphlib.Graph();
  g.setGraph({
    rankdir: opts.rankdir ?? "LR",
    nodesep: opts.nodeSpacing,
    ranksep: opts.layerSpacing,
    marginx: 24,
    marginy: 24,
  });
  g.setDefaultEdgeLabel(() => ({}));

  for (const n of nodes) {
    const height = opts.heights[n.id] ?? 60;
    g.setNode(n.id, { width: opts.nodeWidth, height });
  }

  for (const e of edges) {
    g.setEdge(e.source, e.target);
  }

  dagre.layout(g);

  const positionedNodes: Node[] = nodes.map((n) => {
    const pos = g.node(n.id);
    return {
      ...n,
      position: { x: pos.x - opts.nodeWidth / 2, y: pos.y - pos.height / 2 },
      width: opts.nodeWidth,
      height: pos.height,
    };
  });

  return { nodes: positionedNodes };
}
