import {
  BaseEdge,
  EdgeLabelRenderer,
  getBezierPath,
  type EdgeProps,
} from "@xyflow/react";

import type { EdgeRoute } from "../lib/layout";

/**
 * Custom React Flow edge that renders a SMOOTH curve through ELK's
 * computed route points (start + bendPoints + end). The curve follows
 * the same avoid-cards path ELK planned for orthogonal routing, but
 * smoothed via Catmull-Rom-to-Bezier conversion so the final visual is
 * a continuous arc instead of a sharp L-bend.
 *
 * Falls back to React Flow's default bezier when no ELK route is
 * available (sidecar-traced column edges, dagre engine, or the user
 * dragged an endpoint and the ELK route's anchored start/end no
 * longer match the live node position).
 */

/**
 * Convert a polyline (array of points the curve passes through) to an
 * SVG path string that draws a smooth cubic-Bezier curve passing
 * through every point.
 *
 * Algorithm: Catmull-Rom-to-Bezier conversion. For each segment
 * P_i → P_{i+1}, we derive cubic control points from the neighbors
 * (P_{i-1}, P_{i+2}). The resulting curve interpolates every input
 * point exactly while staying smooth (C1-continuous) across segments.
 *
 * Tension of 1/6 (the standard Catmull-Rom factor) keeps the curve
 * close to the polyline — important here because ELK's bendPoints
 * are positioned to avoid cards, and we don't want the smoothing to
 * bow the curve back into a card body.
 */
function catmullRomPath(points: { x: number; y: number }[]): string {
  if (points.length < 2) return "";
  if (points.length === 2) {
    return `M ${points[0].x} ${points[0].y} L ${points[1].x} ${points[1].y}`;
  }
  let path = `M ${points[0].x} ${points[0].y}`;
  for (let i = 0; i < points.length - 1; i++) {
    const p0 = points[i - 1] ?? points[i];
    const p1 = points[i];
    const p2 = points[i + 1];
    const p3 = points[i + 2] ?? points[i + 1];
    const cp1x = p1.x + (p2.x - p0.x) / 6;
    const cp1y = p1.y + (p2.y - p0.y) / 6;
    const cp2x = p2.x - (p3.x - p1.x) / 6;
    const cp2y = p2.y - (p3.y - p1.y) / 6;
    path += ` C ${cp1x} ${cp1y}, ${cp2x} ${cp2y}, ${p2.x} ${p2.y}`;
  }
  return path;
}

export function ElkRoutedEdge({
  id,
  sourceX,
  sourceY,
  targetX,
  targetY,
  sourcePosition,
  targetPosition,
  data,
  style,
  markerEnd,
  label,
  labelStyle,
  labelBgStyle,
}: EdgeProps) {
  const route = (data as { route?: EdgeRoute } | undefined)?.route;

  // Detect drag: if the React-Flow-supplied handle position (which
  // follows the live node) diverges from ELK's predicted attachment
  // by more than a node-radius's worth, the user has dragged an
  // endpoint and ELK's anchored route is stale. Fall back to default
  // bezier so the curve tracks the dragged node in real time.
  const DRAG_THRESHOLD = 60;
  const isDragged =
    route !== undefined &&
    (Math.hypot(sourceX - route.startPoint.x, sourceY - route.startPoint.y) >
      DRAG_THRESHOLD ||
      Math.hypot(targetX - route.endPoint.x, targetY - route.endPoint.y) >
        DRAG_THRESHOLD);

  let path: string;
  let labelX: number;
  let labelY: number;

  if (route && !isDragged) {
    // Smooth curve through ELK's route. startPoint and endPoint are
    // ELK's per-edge attachments on the card border (so parallel
    // edges to the same card spread out instead of converging at one
    // handle), bendPoints are the avoid-cards interior route.
    const points = [route.startPoint, ...route.bendPoints, route.endPoint];
    path = catmullRomPath(points);
    if (route.bendPoints.length > 0) {
      const mid = route.bendPoints[Math.floor(route.bendPoints.length / 2)];
      labelX = mid.x;
      labelY = mid.y;
    } else {
      labelX = (route.startPoint.x + route.endPoint.x) / 2;
      labelY = (route.startPoint.y + route.endPoint.y) / 2;
    }
  } else {
    // Default bezier — same renderer React Flow uses when `type` is
    // unset. Triggered when the edge has no ELK route or when the
    // user has dragged either endpoint past the threshold.
    [path, labelX, labelY] = getBezierPath({
      sourceX,
      sourceY,
      sourcePosition,
      targetX,
      targetY,
      targetPosition,
    });
  }

  return (
    <>
      <BaseEdge id={id} path={path} style={style} markerEnd={markerEnd} />
      {label && (
        <EdgeLabelRenderer>
          <div
            style={{
              position: "absolute",
              transform: `translate(-50%, -50%) translate(${labelX}px, ${labelY}px)`,
              pointerEvents: "all",
              ...labelBgStyle,
              padding: "1px 4px",
              borderRadius: 3,
              fontSize: 10,
            }}
          >
            <span style={labelStyle}>{label}</span>
          </div>
        </EdgeLabelRenderer>
      )}
    </>
  );
}
