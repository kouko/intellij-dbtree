import { Handle, Position, type Node, type NodeProps } from "@xyflow/react";

import type { Theme } from "../lib/theme";

/**
 * Synthetic placeholder node that stands in for "+N upstream / downstream
 * models that exist in the dbt graph but were cropped by the current
 * hop budget". Rendered as a small dashed bubble alongside the real
 * card it represents missing neighbours of, with a single dashed edge
 * back to that real card so dagre / ELK lays it out in the natural
 * upstream / downstream column.
 */
export interface HiddenNeighboursGhostData extends Record<string, unknown> {
  count: number;
  side: "upstream" | "downstream";
  theme: Theme;
  cardWidth: number;
}

export type HiddenNeighboursGhostType = Node<
  HiddenNeighboursGhostData,
  "dbtGhost"
>;

export const HIDDEN_GHOST_HEIGHT = 44;

export function HiddenNeighboursGhost({
  data,
}: NodeProps<HiddenNeighboursGhostType>) {
  const t = data.theme;
  // Upstream ghost sits LEFT of its real card → it acts as the
  // *source* of an edge going RIGHT into the real card. Downstream
  // ghost mirrors. Either way, only one handle is needed per ghost
  // (the other side never connects to anything).
  return (
    <div
      title={
        data.side === "upstream"
          ? `${data.count} upstream model${data.count === 1 ? "" : "s"} exist in the dbt graph but are outside the current up_hops range`
          : `${data.count} downstream model${data.count === 1 ? "" : "s"} exist in the dbt graph but are outside the current down_hops range`
      }
      style={{
        width: data.cardWidth,
        height: HIDDEN_GHOST_HEIGHT,
        boxSizing: "border-box",
        padding: "10px 14px",
        borderRadius: 10,
        border: `2px dashed ${t.toolbarTextSubtle}`,
        background: "transparent",
        color: t.toolbarTextSubtle,
        fontFamily: "ui-sans-serif, system-ui, -apple-system, sans-serif",
        fontSize: 12,
        fontStyle: "italic",
        textAlign: "center",
        opacity: 0.8,
        display: "flex",
        alignItems: "center",
        justifyContent: "center",
        userSelect: "none",
        cursor: "help",
      }}
    >
      {data.side === "upstream" ? (
        <Handle
          type="source"
          position={Position.Right}
          style={{
            background: t.toolbarTextSubtle,
            width: 12,
            height: 12,
            border: `2px solid ${t.toolbarBg}`,
            opacity: 0.6,
          }}
        />
      ) : (
        <Handle
          type="target"
          position={Position.Left}
          style={{
            background: t.toolbarTextSubtle,
            width: 12,
            height: 12,
            border: `2px solid ${t.toolbarBg}`,
            opacity: 0.6,
          }}
        />
      )}
      +{data.count} hidden{" "}
      {data.side === "upstream" ? "upstream" : "downstream"}
    </div>
  );
}
