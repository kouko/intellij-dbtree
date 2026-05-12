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
 * Endpoint anchoring: the path's start and end always come from
 * React Flow's live sourceX/Y and targetX/Y (handle positions on
 * the current node), not from ELK's anchored startPoint/endPoint.
 * This way the edge always stays attached to the card the user
 * just dragged. The trade-off is that we lose ELK's per-edge
 * attachment spreading along the card border — parallel edges
 * converge at the same handle. ELK's interior bendPoints still win
 * for card avoidance.
 *
 * Falls back to React Flow's default bezier when no ELK route is
 * available (sidecar-traced column edges, dagre engine).
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

  let path: string;
  let labelX: number;
  let labelY: number;

  if (route) {
    // Anchor start/end at React Flow's live handle positions so the
    // edge stays glued to the card after a drag; weave through ELK's
    // bendPoints in between for the avoid-cards interior route.
    const points = [
      { x: sourceX, y: sourceY },
      ...route.bendPoints,
      { x: targetX, y: targetY },
    ];
    path = roundedCornerPath(points, CORNER_RADIUS);
    if (route.bendPoints.length > 0) {
      const mid = route.bendPoints[Math.floor(route.bendPoints.length / 2)];
      labelX = mid.x;
      labelY = mid.y;
    } else {
      labelX = (sourceX + targetX) / 2;
      labelY = (sourceY + targetY) / 2;
    }
  } else {
    // Default bezier — same renderer React Flow uses when `type` is
    // unset. Triggered when the edge has no ELK route at all.
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
