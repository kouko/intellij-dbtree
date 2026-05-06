import { Handle, Position, type NodeProps, type Node } from "@xyflow/react";
import type { ColumnSpec, ModelLayer } from "../types";
import type { Theme } from "../lib/theme";

export interface DbtModelNodeData extends Record<string, unknown> {
  unique_id: string;
  name: string;
  package_name: string;
  layer?: ModelLayer;
  columns: ColumnSpec[];
  expanded: boolean;
  highlightedColumns: Set<string>;
  onLineagePath: boolean;
  isSelectedModel: boolean;
  theme: Theme;
  cardWidth: number;
  onToggleExpanded: (uniqueId: string) => void;
  onColumnClick: (uniqueId: string, column: string) => void;
  onOpenFile?: (uniqueId: string) => void;
}

export type DbtModelNodeType = Node<DbtModelNodeData, "dbtModel">;

export function DbtModelNode({ data }: NodeProps<DbtModelNodeType>) {
  const layer = data.layer ?? "staging";
  const colors = data.theme.layers[layer];
  const t = data.theme;
  const openable = !!data.onOpenFile;

  // Fixed card width — long names wrap to multiple header lines instead of
  // being truncated. Matches the user's rule: a model name must always be
  // fully visible.
  const cardStyle: React.CSSProperties = {
    borderRadius: 10,
    border: `2px solid ${data.isSelectedModel ? t.selectedBorder : colors.border}`,
    background: colors.bg,
    width: data.cardWidth,
    fontFamily: "ui-sans-serif, system-ui, -apple-system, sans-serif",
    fontSize: 12,
    boxShadow: data.onLineagePath
      ? `0 0 0 3px ${t.highlightBg}, 0 4px 12px rgba(0,0,0,0.08)`
      : "0 1px 3px rgba(0,0,0,0.06)",
    transition: "box-shadow 120ms",
    cursor: openable ? "pointer" : "default",
  };

  const tooltip = openable
    ? `${data.name}\n${data.unique_id}\n\nClick to open file · click ▸/▾ to expand columns`
    : `${data.name}\n${data.unique_id}`;

  return (
    <div
      style={cardStyle}
      title={tooltip}
      onClick={() => data.onOpenFile?.(data.unique_id)}
    >
      <Handle type="target" position={Position.Left} style={{ background: colors.chip }} />
      <Handle type="source" position={Position.Right} style={{ background: colors.chip }} />

      <header
        style={{
          padding: "8px 12px",
          borderBottom: data.expanded ? `1px solid ${colors.border}` : "none",
          display: "flex",
          alignItems: "center",
          justifyContent: "space-between",
          gap: 8,
        }}
      >
        <div
          style={{
            display: "flex",
            alignItems: "center",
            gap: 6,
            minWidth: 0,
            flex: 1,
          }}
        >
          <span
            style={{
              fontSize: 10,
              fontWeight: 600,
              color: "white",
              background: colors.chip,
              padding: "1px 6px",
              borderRadius: 4,
              textTransform: "uppercase",
              letterSpacing: 0.4,
              flexShrink: 0,
            }}
          >
            {layer}
          </span>
          <span
            style={{
              fontWeight: 600,
              color: colors.text,
              wordBreak: "break-word",
              lineHeight: 1.3,
              minWidth: 0,
            }}
          >
            {data.name}
          </span>
        </div>
        <button
          type="button"
          onClick={(e) => {
            e.stopPropagation();
            data.onToggleExpanded(data.unique_id);
          }}
          title={data.expanded ? "Collapse columns" : "Expand columns"}
          style={{
            background: "transparent",
            border: "none",
            padding: "2px 6px",
            color: t.toolbarTextMuted,
            cursor: "pointer",
            fontSize: 11,
            borderRadius: 3,
            display: "flex",
            alignItems: "center",
            gap: 2,
            flexShrink: 0,
          }}
        >
          {data.expanded ? "▾" : "▸"} {data.columns.length}
        </button>
      </header>

      {data.expanded && (
        <ul style={{ listStyle: "none", margin: 0, padding: "4px 0" }}>
          {data.columns.map((col) => {
            const highlighted = data.highlightedColumns.has(col.name);
            return (
              <li
                key={col.name}
                onClick={(e) => {
                  e.stopPropagation();
                  data.onColumnClick(data.unique_id, col.name);
                }}
                title={col.type ? `${col.name}: ${col.type}` : col.name}
                style={{
                  padding: "3px 12px",
                  display: "flex",
                  justifyContent: "space-between",
                  gap: 8,
                  cursor: "pointer",
                  background: highlighted ? t.highlightBg : "transparent",
                  borderLeft: highlighted ? `3px solid ${t.edgeHighlight}` : "3px solid transparent",
                  fontFamily: "ui-monospace, SFMono-Regular, monospace",
                  fontSize: 11,
                  color: highlighted ? t.highlightText : colors.text,
                  fontWeight: highlighted ? 600 : 400,
                }}
              >
                <span style={{ overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>
                  {col.name}
                </span>
                {col.type && (
                  <span style={{ color: t.toolbarTextSubtle, fontSize: 10 }}>{col.type}</span>
                )}
              </li>
            );
          })}
        </ul>
      )}
    </div>
  );
}
