import dagre from "dagre";
import type { Edge, Node } from "@xyflow/react";

/**
 * Run dagre on the model-level DAG and return positioned nodes.
 *
 * Dagre measures in pixels, so we feed it the actual rendered size of each
 * node (computed from the columns count when expanded). xyflow doesn't know
 * the size until after first render, so we estimate here based on `expanded`
 * state and let xyflow auto-fit.
 */
export interface LayoutOptions {
  rankdir?: "LR" | "TB";
  nodeWidth: number;
  rowHeight: number;
  headerHeight: number;
  nodesepX: number;
  ranksepY: number;
  /** Mapping unique_id -> column count when expanded (0 if collapsed). */
  expandedColumnCount: Record<string, number>;
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
    const cols = opts.expandedColumnCount[n.id] ?? 0;
    const height = opts.headerHeight + cols * opts.rowHeight + 12;
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
