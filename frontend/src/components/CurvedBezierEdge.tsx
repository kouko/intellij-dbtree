import {
  BaseEdge,
  EdgeLabelRenderer,
  Position,
  type EdgeProps,
} from "@xyflow/react";

/**
 * Single cubic-Bezier edge identical in form to React Flow's default
 * bezier, but with a longer control arm — α = 0.7 × dx instead of
 * the built-in 0.5 × dx — so the curve bows more.
 *
 * Why we don't just set `curvature` on the default bezier: React
 * Flow's `getBezierPath` hard-codes the offset at `0.5 * distance`
 * when target is "ahead of" source (the typical DAG case); the
 * `curvature` parameter only kicks in for reverse edges. Bumping the
 * coefficient requires our own thin renderer.
 *
 * Over/under crossings: each edge renders as two stacked paths. The
 * first ("halo") is wider and stroked in the canvas background
 * colour passed via [CurvedBezierData.haloColor]; it visually
 * erases any previously-drawn edge underneath. The second is the
 * normal coloured stroke. Because React Flow renders edges in array
 * order, later edges' halos cut a gap into earlier edges where they
 * cross, producing a "rope-over-rope" look without filters.
 */

const CURVATURE_FACTOR = 0.7;
const HALO_EXTRA_WIDTH = 4;

interface CurvedBezierData {
  /** Canvas background colour — used to "erase" the underlying edge at
   *  crossings, producing the over/under effect. */
  haloColor?: string;
}

function controlOffset(distance: number, curvature: number): number {
  if (distance >= 0) return curvature * distance;
  return curvature * 25 * Math.sqrt(-distance);
}

function controlPoint(
  pos: Position,
  x1: number,
  y1: number,
  x2: number,
  y2: number,
  curvature: number,
): [number, number] {
  switch (pos) {
    case Position.Left:
      return [x1 - controlOffset(x1 - x2, curvature), y1];
    case Position.Right:
      return [x1 + controlOffset(x2 - x1, curvature), y1];
    case Position.Top:
      return [x1, y1 - controlOffset(y1 - y2, curvature)];
    case Position.Bottom:
      return [x1, y1 + controlOffset(y2 - y1, curvature)];
  }
}

export function CurvedBezierEdge({
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
  const [c1x, c1y] = controlPoint(
    sourcePosition ?? Position.Right,
    sourceX,
    sourceY,
    targetX,
    targetY,
    CURVATURE_FACTOR,
  );
  const [c2x, c2y] = controlPoint(
    targetPosition ?? Position.Left,
    targetX,
    targetY,
    sourceX,
    sourceY,
    CURVATURE_FACTOR,
  );
  const path = `M ${sourceX} ${sourceY} C ${c1x} ${c1y}, ${c2x} ${c2y}, ${targetX} ${targetY}`;
  const labelX = (sourceX + targetX) / 2;
  const labelY = (sourceY + targetY) / 2;

  const haloColor = (data as CurvedBezierData | undefined)?.haloColor;
  const strokeWidth =
    typeof style?.strokeWidth === "number" ? style.strokeWidth : 1.5;
  const haloStyle: React.CSSProperties | undefined = haloColor
    ? {
        stroke: haloColor,
        strokeWidth: strokeWidth + HALO_EXTRA_WIDTH,
        strokeLinecap: "round",
        fill: "none",
      }
    : undefined;

  return (
    <>
      {haloStyle && (
        <BaseEdge id={`${id}-halo`} path={path} style={haloStyle} />
      )}
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
