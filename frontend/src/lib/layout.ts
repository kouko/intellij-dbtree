import dagre from "dagre";
import type { Edge, Node } from "@xyflow/react";

/**
 * Run dagre on the model-level DAG and return positioned nodes.
 *
 * Nodes have a fixed width but a per-node height — the caller pre-computes
 * heights since they depend on (a) how many name lines wrap inside the
 * card header at the chosen width and (b) whether the card is expanded
 * with its column list.
 */
export interface LayoutOptions {
  rankdir?: "LR" | "TB";
  nodeWidth: number;
  nodesepX: number;
  ranksepY: number;
  /** Total rendered height per node uniqueId. */
  heights: Record<string, number>;
}

export function layoutModelGraph(
  nodes: Node[],
  edges: Edge[],
  opts: LayoutOptions,
): Node[] {
  const g = new dagre.graphlib.Graph();
  g.setGraph({
    rankdir: opts.rankdir ?? "LR",
    nodesep: opts.nodesepX,
    ranksep: opts.ranksepY,
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

  return nodes.map((n) => {
    const pos = g.node(n.id);
    return {
      ...n,
      position: { x: pos.x - opts.nodeWidth / 2, y: pos.y - pos.height / 2 },
    };
  });
}
