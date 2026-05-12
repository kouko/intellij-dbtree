import ELK from "elkjs/lib/elk.bundled.js";
import type { ElkEdgeSection, ElkExtendedEdge } from "elkjs/lib/elk-api";
import type { Edge, Node } from "@xyflow/react";

export interface LayoutOptions {
  rankdir?: "LR" | "TB";
  nodeWidth: number;
  nodesepX: number;
  ranksepY: number;
  heights: Record<string, number>;
}

/** ELK-computed route for one edge, in flow-coordinate space. */
export interface EdgeRoute {
  /** Where the edge attaches to the source node's boundary. */
  startPoint: { x: number; y: number };
  /** Intermediate bend points (corners), in order from source → target.
   *  Empty when ELK chose a straight line. */
  bendPoints: { x: number; y: number }[];
  /** Where the edge attaches to the target node's boundary. */
  endPoint: { x: number; y: number };
}

export interface LayoutResult {
  nodes: Node[];
  /** Edge id → computed route. Edges not in this map (e.g. column-
   *  lineage edges added after layout) should fall back to React
   *  Flow's built-in routing. */
  edgeRoutes: Map<string, EdgeRoute>;
}

const elk = new ELK();

export async function layoutModelGraph(
  nodes: Node[],
  edges: Edge[],
  opts: LayoutOptions,
): Promise<LayoutResult> {
  if (nodes.length === 0) return { nodes: [], edgeRoutes: new Map() };

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
      // Orthogonal routing produces bendPoints that step around every
      // unrelated node. The render layer ([ElkRoutedEdge]) rounds each
      // corner so the final visual is straight segments joined by
      // short arcs that still respect ELK's avoid-cards path.
      "elk.edgeRouting": "ORTHOGONAL",
      // Spacing chosen so the corner arcs at each bend don't bring the
      // edge inside a neighbouring card. The orthogonal bend points
      // sit 60px out from each card, and the rounding radius is
      // far smaller than that buffer.
      "elk.spacing.edgeEdge": "30",
      "elk.layered.spacing.edgeEdgeBetweenLayers": "30",
      "elk.spacing.edgeNode": "60",
      "elk.layered.spacing.edgeNodeBetweenLayers": "60",
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

  const edgeRoutes = new Map<string, EdgeRoute>();
  const resultEdges = (result.edges ?? []) as ElkExtendedEdge[];
  for (const e of resultEdges) {
    const section: ElkEdgeSection | undefined = e.sections?.[0];
    if (!section?.startPoint || !section?.endPoint) continue;
    edgeRoutes.set(e.id, {
      startPoint: { x: section.startPoint.x, y: section.startPoint.y },
      endPoint: { x: section.endPoint.x, y: section.endPoint.y },
      bendPoints: (section.bendPoints ?? []).map((p: { x: number; y: number }) => ({
        x: p.x,
        y: p.y,
      })),
    });
  }

  return { nodes: positionedNodes, edgeRoutes };
}
