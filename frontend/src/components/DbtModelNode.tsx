import { useState } from "react";
import { Handle, Position, type NodeProps, type Node } from "@xyflow/react";
import type { ColumnSpec, ModelLayer } from "../types";
import { normalizeLayer, type Theme } from "../lib/theme";

export interface DbtModelNodeData extends Record<string, unknown> {
  unique_id: string;
  name: string;
  package_name: string;
  /** Canonical color bucket. */
  layer?: ModelLayer;
  /** Raw folder name shown on the chip; falls back to [layer]. */
  folder?: string;
  /** dbt materialization (table / view / incremental / …); drives type badge. */
  materialization?: string;
  columns: ColumnSpec[];
  expanded: boolean;
  /**
   * True while the Kotlin side is computing this model's column list via
   * the sqlglot sidecar. The expanded card shows a "Parsing SQL…" hint
   * instead of an empty list.
   */
  columnsPending?: boolean;
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
  const layer = normalizeLayer(data.layer);
  const colors = data.theme.layers[layer];
  // Chip displays the raw folder name (preserves dbt namespacing like
  // `marts_msd`) and falls back to the canonical layer when older payloads
  // don't include `folder`.
  const chipText = data.folder ?? layer;
  const t = data.theme;
  const openable = !!data.onOpenFile;
  const [hover, setHover] = useState(false);

  // Fixed card width — long names wrap to multiple header lines instead of
  // being truncated. Matches the user's rule: a model name must always be
  // fully visible.
  //
  // Hover state: thicker outline using the layer's bright chip color.
  // `outline` doesn't affect layout, so the card doesn't shift on hover.
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
    outline: hover && openable
      ? `2px solid ${data.isSelectedModel ? t.selectedBorder : colors.chip}`
      : "none",
    outlineOffset: 0,
    transition: "outline-color 80ms",
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
      onMouseEnter={() => setHover(true)}
      onMouseLeave={() => setHover(false)}
    >
      <Handle type="target" position={Position.Left} style={{ background: colors.chip }} />
      <Handle type="source" position={Position.Right} style={{ background: colors.chip }} />

      <header
        style={{
          padding: "8px 12px",
          borderBottom: data.expanded ? `1px solid ${colors.border}` : "none",
          display: "flex",
          flexDirection: "column",
          gap: 4,
        }}
      >
        {/*
          Top row: chip + chevron. Stacking the chip ABOVE the model name
          (instead of inline beside it) gives the name the full card width
          for wrapping. Long folder labels like `export_to_googlesheets`
          would otherwise eat ~150px of horizontal budget and squeeze the
          name into a 2-3 line stack of fragments.
        */}
        <div
          style={{
            display: "flex",
            alignItems: "center",
            justifyContent: "space-between",
            gap: 8,
            minWidth: 0,
          }}
        >
          <div
            style={{
              display: "flex",
              alignItems: "center",
              gap: 4,
              minWidth: 0,
              flex: 1,
            }}
          >
            <MaterializationBadge value={data.materialization} theme={t} />
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
                // Defensive ceiling: an absurdly long folder (>30 chars) gets
                // ellipsised rather than overflowing the card. Real-world
                // folders top out around 22 chars (`export_to_googlesheets`).
                maxWidth: "100%",
                overflow: "hidden",
                textOverflow: "ellipsis",
                whiteSpace: "nowrap",
              }}
              title={chipText}
            >
              {chipText}
            </span>
          </div>
          <ChevronButton
            expanded={data.expanded}
            columnCount={data.columns.length}
            theme={t}
            onClick={(e) => {
              e.stopPropagation();
              data.onToggleExpanded(data.unique_id);
            }}
          />
        </div>
        {/* Bottom row: full-width model name. */}
        <span
          style={{
            fontWeight: 600,
            color: colors.text,
            wordBreak: "break-word",
            lineHeight: 1.3,
          }}
        >
          {data.name}
        </span>
      </header>

      {data.expanded && data.columns.length === 0 && data.columnsPending && (
        <div
          style={{
            padding: "8px 12px",
            color: t.toolbarTextSubtle,
            fontSize: 11,
            fontStyle: "italic",
          }}
        >
          Parsing SQL…
        </div>
      )}
      {data.expanded && data.columns.length > 0 && (
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

/**
 * Map a dbt materialization string to its user-facing single-letter label.
 * Returning null suppresses the badge entirely (sources, missing config,
 * unknown custom-adapter materializations).
 *
 * Centralised here so the wire format can carry whatever string dbt emits,
 * while the UI controls which ones are worth a badge slot. Add new entries
 * as new materializations become common in the wild.
 */
export function materializationLetter(value: string | undefined): string | null {
  switch (value) {
    case "table":
      return "T";
    case "view":
      return "V";
    case "incremental":
      return "I";
    case "ephemeral":
      return "E";
    case "materialized_view":
      return "MV";
    default:
      return null;
  }
}

function MaterializationBadge({
  value,
  theme,
}: {
  value: string | undefined;
  theme: Theme;
}) {
  const letter = materializationLetter(value);
  if (letter === null) return null;
  // Outlined pill in theme-neutral colors so it reads as secondary metadata
  // and doesn't compete visually with the colourful folder chip.
  return (
    <span
      title={`Materialization: ${value}`}
      style={{
        fontSize: 10,
        fontWeight: 700,
        color: theme.toolbarText,
        background: "transparent",
        border: `1px solid ${theme.toolbarTextSubtle}`,
        padding: "0 4px",
        borderRadius: 3,
        letterSpacing: 0.3,
        flexShrink: 0,
        lineHeight: 1.4,
        fontVariant: "tabular-nums",
      }}
    >
      {letter}
    </span>
  );
}

function ChevronButton({
  expanded,
  columnCount,
  theme,
  onClick,
}: {
  expanded: boolean;
  columnCount: number;
  theme: Theme;
  onClick: (e: React.MouseEvent) => void;
}) {
  const [hover, setHover] = useState(false);
  return (
    <button
      type="button"
      onClick={onClick}
      onMouseEnter={() => setHover(true)}
      onMouseLeave={() => setHover(false)}
      title={expanded ? "Collapse columns" : "Expand columns"}
      style={{
        background: hover ? theme.buttonHoverBg : "transparent",
        border: "none",
        padding: "5px 10px",
        color: theme.toolbarText,
        cursor: "pointer",
        fontSize: 12,
        borderRadius: 4,
        display: "flex",
        alignItems: "center",
        gap: 4,
        flexShrink: 0,
        minWidth: 40,
        justifyContent: "center",
        transition: "background 80ms",
      }}
    >
      <span style={{ fontSize: 11 }}>{expanded ? "▾" : "▸"}</span>
      <span style={{ fontVariantNumeric: "tabular-nums" }}>{columnCount}</span>
    </button>
  );
}
