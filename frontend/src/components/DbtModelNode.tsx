import { useEffect, useRef, useState } from "react";
import { computeScrollTopForCentering } from "../lib/column-scroll";
import { Handle, Position, type NodeProps, type Node } from "@xyflow/react";
import type { ColumnSpec, ModelLayer } from "../types";
import { normalizeLayer, type Theme } from "../lib/theme";
import { COLUMN_LIST_MAX_HEIGHT, COLUMN_SCROLL_THRESHOLD } from "../App";

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
  const columnListRef = useRef<HTMLUListElement>(null);
  const isScrollable = data.columns.length > COLUMN_SCROLL_THRESHOLD;

  useEffect(() => {
    if (!isScrollable) return;
    if (data.highlightedColumns.size === 0) return;
    const ul = columnListRef.current;
    if (!ul) return;

    const items = ul.querySelectorAll<HTMLLIElement>('li[data-highlighted="true"]');
    if (items.length === 0) return;

    const first = items[0];
    const last = items[items.length - 1];
    const firstTop = first.offsetTop;
    const lastBottom = last.offsetTop + last.offsetHeight;

    const top = computeScrollTopForCentering({
      firstTop,
      lastBottom,
      clientHeight: ul.clientHeight,
      scrollHeight: ul.scrollHeight,
    });

    ul.scrollTo({ top, behavior: "smooth" });
  }, [data.highlightedColumns, isScrollable, data.expanded]);

  // Background tint: when the card is selected or hovered, mix the
  // layer's own border tone on top of its pale bg so the card reads
  // as highlighted even at low zoom (where the 2px border / outline
  // would otherwise disappear). Both states use the *same* overlay
  // (the layer's border color at ~40% alpha — `66` in 8-digit hex);
  // selected vs hover is distinguished by the border / outline color
  // (orange selectedBorder vs chip color), not by background, so
  // white chip text stays readable in both states.
  const tinted = data.isSelectedModel || (hover && openable);
  const cardBackground = tinted
    ? `linear-gradient(${colors.border}66, ${colors.border}66), ${colors.bg}`
    : colors.bg;

  // box-shadow stack (composed back-to-front, first item drawn on top):
  //   - selected: 4px orange ring directly outside the 2px border, so
  //     the visible "border" reads as ~6px thick (~3× the unselected
  //     state). Doing this with box-shadow rather than a thicker
  //     border keeps the card's layout size constant — bumping the
  //     real border would shift every selected card by 4px and force
  //     surrounding cards to reflow.
  //   - on-lineage-path: highlight halo, extended when also selected
  //     so it sits beyond the selected ring.
  const shadowParts: string[] = [];
  if (data.isSelectedModel) {
    shadowParts.push(`0 0 0 4px ${t.selectedBorder}`);
  }
  if (data.onLineagePath) {
    const haloSpread = data.isSelectedModel ? 7 : 3;
    shadowParts.push(`0 0 0 ${haloSpread}px ${t.highlightBg}`);
    shadowParts.push("0 4px 12px rgba(0,0,0,0.08)");
  } else {
    shadowParts.push("0 1px 3px rgba(0,0,0,0.06)");
  }

  const cardStyle: React.CSSProperties = {
    borderRadius: 10,
    border: `2px solid ${data.isSelectedModel ? t.selectedBorder : colors.border}`,
    background: cardBackground,
    width: data.cardWidth,
    fontFamily: "ui-sans-serif, system-ui, -apple-system, sans-serif",
    fontSize: 12,
    boxShadow: shadowParts.join(", "),
    // Hover outline only when not selected — the 4px selected ring
    // already dominates the visual, an additional outline would
    // overlap it and look noisy.
    outline:
      hover && openable && !data.isSelectedModel
        ? `2px solid ${colors.chip}`
        : "none",
    outlineOffset: 0,
    transition: "background 80ms, outline-color 80ms",
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
      <Handle
        type="target"
        position={Position.Left}
        style={{
          background: colors.chip,
          width: 18,
          height: 18,
          border: `2px solid ${t.toolbarBg}`,
        }}
      />
      <Handle
        type="source"
        position={Position.Right}
        style={{
          background: colors.chip,
          width: 18,
          height: 18,
          border: `2px solid ${t.toolbarBg}`,
        }}
      />

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
            <MaterializationBadge value={data.materialization} theme={t} />
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
        <ul
          ref={columnListRef}
          className="nowheel column-list"
          style={{
            listStyle: "none",
            margin: 0,
            marginRight: isScrollable ? 2 : 0,
            padding: "4px 0",
            maxHeight: isScrollable ? COLUMN_LIST_MAX_HEIGHT : undefined,
            overflowY: isScrollable ? "auto" : "visible",
          }}
        >
          {data.columns.map((col) => {
            const highlighted = data.highlightedColumns.has(col.name);
            return (
              <li
                key={col.name}
                data-highlighted={highlighted ? "true" : undefined}
                onClick={(e) => {
                  e.stopPropagation();
                  data.onColumnClick(data.unique_id, col.name);
                }}
                title={col.type ? `${col.name}: ${col.type}` : col.name}
                style={{
                  padding: "3px 12px",
                  display: "flex",
                  justifyContent: "space-between",
                  alignItems: "flex-start",
                  gap: 8,
                  cursor: "pointer",
                  background: highlighted ? t.highlightBg : "transparent",
                  borderLeft: highlighted ? `3px solid ${t.edgeHighlight}` : "3px solid transparent",
                  fontFamily: "ui-monospace, SFMono-Regular, monospace",
                  fontSize: 10,
                  lineHeight: 1.3,
                  color: highlighted ? t.highlightText : colors.text,
                  fontWeight: highlighted ? 600 : 400,
                }}
              >
                <span
                  style={{
                    flex: "1 1 auto",
                    minWidth: 0,
                    wordBreak: "break-all",
                    textAlign: "left",
                  }}
                >
                  {col.name}
                </span>
                {col.type && (
                  <span
                    style={{
                      flex: "0 1 auto",
                      maxWidth: "50%",
                      color: t.toolbarTextSubtle,
                      fontSize: 9,
                      lineHeight: 1.3,
                      wordBreak: "break-all",
                      textAlign: "right",
                    }}
                  >
                    {col.type}
                  </span>
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
