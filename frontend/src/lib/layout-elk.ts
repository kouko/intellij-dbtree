import ELK from "elkjs/lib/elk.bundled.js";
import type { Edge, Node } from "@xyflow/react";

export interface LayoutOptions {
  rankdir?: "LR" | "TB";
  nodeWidth: number;
  /** Gap between adjacent nodes within the same layer. For LR this is
   *  vertical, for TB horizontal. */
  nodeSpacing: number;
  /** Gap between adjacent layers. For LR this is horizontal, for TB
   *  vertical. */
  layerSpacing: number;
  heights: Record<string, number>;
}

export interface LayoutResult {
  nodes: Node[];
}

const elk = new ELK();

/**
 * Run the ELK layered layout on the model-level DAG and return
 * positioned nodes. Edge geometry is intentionally not extracted —
 * the renderer draws every edge with React Flow's default bezier so
 * the curve is parameterised purely by the live source/target
 * handle positions and the same algorithm applies whether the user
 * has dragged or not.
 */
export async function layoutModelGraph(
  nodes: Node[],
  edges: Edge[],
  opts: LayoutOptions,
): Promise<LayoutResult> {
  if (nodes.length === 0) return { nodes: [] };

  const direction = (opts.rankdir ?? "LR") === "LR" ? "RIGHT" : "DOWN";
  const graph = {
    id: "root",
    layoutOptions: {
      "elk.algorithm": "layered",
      "elk.direction": direction,
      "elk.layered.spacing.nodeNodeBetweenLayers": String(opts.layerSpacing),
      "elk.spacing.nodeNode": String(opts.nodeSpacing),
      "elk.layered.layerUnzipping.strategy": "ALTERNATING",
      "elk.layered.layerUnzipping.layerSplit": "2",
      "elk.layered.layerUnzipping.resetOnLongEdges": "true",
      "elk.layered.nodePlacement.strategy": "BRANDES_KOEPF",
      "elk.layered.thoroughness": "10",
      // Enable edge routing purely as a placement hint. We don't read
      // ELK's edge geometry back — the renderer still draws cubic
      // Beziers — but with these options set, BRANDES_KOEPF reserves
      // edge channels and pushes unrelated nodes out of those
      // channels, so the bezier between any two handles is unlikely
      // to cross a third card.
      "elk.edgeRouting": "ORTHOGONAL",
      "elk.spacing.edgeNode": "60",
      "elk.layered.spacing.edgeNodeBetweenLayers": "60",
      "elk.spacing.edgeEdge": "30",
      "elk.layered.spacing.edgeEdgeBetweenLayers": "30",
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

  const positionedNodes: Node[] = nodes.map((n) => {
    const c = childById.get(n.id);
    const height = opts.heights[n.id] ?? 60;
    return {
      ...n,
      position: { x: c?.x ?? 0, y: c?.y ?? 0 },
      width: opts.nodeWidth,
      height,
    };
  });

  return { nodes: positionedNodes };
}
