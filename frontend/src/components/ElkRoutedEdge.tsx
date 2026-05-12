import {
  BaseEdge,
  EdgeLabelRenderer,
  getBezierPath,
  type EdgeProps,
} from "@xyflow/react";

import type { EdgeRoute } from "../lib/layout";

/**
 * Custom React Flow edge that follows ELK's avoid-cards orthogonal
 * route but rounds each corner so the final visual is soft straight
 * segments joined by short arcs, not sharp L-bends.
 *
 * Falls back to React Flow's default bezier when no ELK route is
 * available (sidecar-traced column edges, dagre engine, or the user
 * dragged an endpoint and the ELK route's anchored start/end no
 * longer match the live node position).
 */

/**
 * Convert a polyline (array of points the curve passes through) to an
 * SVG path that keeps the straight segments between corners but rounds
 * each corner with a short quadratic arc.
 *
 * Why not Catmull-Rom: ELK's ORTHOGONAL output places consecutive
 * bendPoints at 90° corners. A through-every-point smoother
 * (Catmull-Rom, basis splines, etc) overshoots at consecutive
 * close-together corners and produces visible S-curves. Corner
 * rounding never overshoots — between any two corners the path is
 * literally a line segment, and at each corner we shorten both
 * incoming and outgoing segments by `radius`, then connect with a
 * quadratic Bezier whose control point is the corner itself.
 *
 * Radius is capped to half the shorter adjacent segment so the
 * rounding never invades the next corner.
 */
function roundedCornerPath(
  points: { x: number; y: number }[],
  radius: number,
): string {
  if (points.length < 2) return "";
  if (points.length === 2) {
    return `M ${points[0].x} ${points[0].y} L ${points[1].x} ${points[1].y}`;
  }
  let path = `M ${points[0].x} ${points[0].y}`;
  for (let i = 1; i < points.length - 1; i++) {
    const prev = points[i - 1];
    const curr = points[i];
    const next = points[i + 1];
    const inDx = curr.x - prev.x;
    const inDy = curr.y - prev.y;
    const inLen = Math.hypot(inDx, inDy);
    const outDx = next.x - curr.x;
    const outDy = next.y - curr.y;
    const outLen = Math.hypot(outDx, outDy);
    // Duplicate points (zero-length segment) — skip rounding,
    // just emit a line to the corner itself.
    if (inLen === 0 || outLen === 0) {
      path += ` L ${curr.x} ${curr.y}`;
      continue;
    }
    const r = Math.min(radius, inLen / 2, outLen / 2);
    const stopX = curr.x - (inDx / inLen) * r;
    const stopY = curr.y - (inDy / inLen) * r;
    const resumeX = curr.x + (outDx / outLen) * r;
    const resumeY = curr.y + (outDy / outLen) * r;
    path += ` L ${stopX} ${stopY} Q ${curr.x} ${curr.y} ${resumeX} ${resumeY}`;
  }
  const last = points[points.length - 1];
  path += ` L ${last.x} ${last.y}`;
  return path;
}

const CORNER_RADIUS = 16;

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
    // Rounded-corner path through ELK's route. startPoint and
    // endPoint are ELK's per-edge attachments on the card border (so
    // parallel edges to the same card spread out instead of converging
    // at one handle), bendPoints are the avoid-cards interior route.
    const points = [route.startPoint, ...route.bendPoints, route.endPoint];
    path = roundedCornerPath(points, CORNER_RADIUS);
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
