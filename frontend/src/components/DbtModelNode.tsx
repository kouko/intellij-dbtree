import { Handle, Position, type NodeProps, type Node } from "@xyflow/react";
import type { ColumnSpec, ModelLayer } from "../types";

export interface DbtModelNodeData extends Record<string, unknown> {
  unique_id: string;
  name: string;
  package_name: string;
  layer?: ModelLayer;
  columns: ColumnSpec[];
  expanded: boolean;
  /** Set of column names currently highlighted (downstream lineage match). */
  highlightedColumns: Set<string>;
  /** Whether this whole node is on the active lineage path. */
  onLineagePath: boolean;
  /** Whether this is the model the user originally selected. */
  isSelectedModel: boolean;
  onToggleExpanded: (uniqueId: string) => void;
  onColumnClick: (uniqueId: string, column: string) => void;
}

export type DbtModelNodeType = Node<DbtModelNodeData, "dbtModel">;

const layerColor: Record<ModelLayer, { border: string; bg: string; chip: string }> = {
  source:       { border: "#94a3b8", bg: "#f1f5f9", chip: "#64748b" },
  staging:      { border: "#60a5fa", bg: "#eff6ff", chip: "#2563eb" },
  intermediate: { border: "#a78bfa", bg: "#f5f3ff", chip: "#7c3aed" },
  marts:        { border: "#34d399", bg: "#ecfdf5", chip: "#059669" },
};

export function DbtModelNode({ data }: NodeProps<DbtModelNodeType>) {
  const layer = data.layer ?? "staging";
  const colors = layerColor[layer];

  const cardStyle: React.CSSProperties = {
    borderRadius: 10,
    border: `2px solid ${data.isSelectedModel ? "#f59e0b" : colors.border}`,
    background: colors.bg,
    minWidth: 220,
    fontFamily: "ui-sans-serif, system-ui, -apple-system, sans-serif",
    fontSize: 12,
    boxShadow: data.onLineagePath
      ? "0 0 0 3px rgba(245, 158, 11, 0.25), 0 4px 12px rgba(0,0,0,0.08)"
      : "0 1px 3px rgba(0,0,0,0.06)",
    opacity: 1,
    transition: "box-shadow 120ms",
  };

  return (
    <div style={cardStyle}>
      <Handle type="target" position={Position.Left} style={{ background: colors.chip }} />
      <Handle type="source" position={Position.Right} style={{ background: colors.chip }} />

      <header
        style={{
          padding: "8px 12px",
          cursor: "pointer",
          borderBottom: data.expanded ? `1px solid ${colors.border}` : "none",
          display: "flex",
          alignItems: "center",
          justifyContent: "space-between",
          gap: 8,
        }}
        onClick={() => data.onToggleExpanded(data.unique_id)}
      >
        <div style={{ display: "flex", alignItems: "center", gap: 6, minWidth: 0 }}>
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
            }}
          >
            {layer}
          </span>
          <span style={{ fontWeight: 600, color: "#0f172a", overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>
            {data.name}
          </span>
        </div>
        <span style={{ color: "#64748b", fontSize: 11 }}>
          {data.expanded ? "▾" : "▸"} {data.columns.length}
        </span>
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
                  background: highlighted ? "rgba(245, 158, 11, 0.18)" : "transparent",
                  borderLeft: highlighted ? "3px solid #f59e0b" : "3px solid transparent",
                  fontFamily: "ui-monospace, SFMono-Regular, monospace",
                  fontSize: 11,
                  color: highlighted ? "#92400e" : "#0f172a",
                  fontWeight: highlighted ? 600 : 400,
                }}
              >
                <span style={{ overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>
                  {col.name}
                </span>
                {col.type && (
                  <span style={{ color: "#94a3b8", fontSize: 10 }}>{col.type}</span>
                )}
              </li>
            );
          })}
        </ul>
      )}
    </div>
  );
}
