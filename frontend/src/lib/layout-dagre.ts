import dagre from "dagre";
import type { Edge, Node } from "@xyflow/react";
import type { EdgeRoute, LayoutResult } from "./layout-elk";

/**
 * Run dagre on the model-level DAG and return positioned nodes.
 *
 * Returns the same [LayoutResult] shape as the ELK engine, with
 * `edgeRoutes` always empty — dagre's edge routing data isn't exposed
 * through dagre/graphlib's public API in a usable shape, so callers
 * that want card-avoiding edge paths must use the ELK engine.
 */
export interface LayoutOptions {
  rankdir?: "LR" | "TB";
  nodeWidth: number;
  nodesepX: number;
  ranksepY: number;
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

  const positionedNodes: Node[] = nodes.map((n) => {
    const pos = g.node(n.id);
    return {
      ...n,
      position: { x: pos.x - opts.nodeWidth / 2, y: pos.y - pos.height / 2 },
      width: opts.nodeWidth,
      height: pos.height,
    };
  });

  const edgeRoutes = new Map<string, EdgeRoute>();
  return { nodes: positionedNodes, edgeRoutes };
}
