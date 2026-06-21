# Model Card Column List — Bounded Height + Auto-Scroll on Lineage Selection

**Date:** 2026-05-14
**Status:** Design — pending implementation
**Scope:** Frontend only (React, `@xyflow/react`)

## Problem

When a model card is expanded in the DAG view, its column `<ul>` renders every column unconstrained. Wide models (50–200+ columns) produce vertical card heights that:

1. Dwarf neighbouring cards and dominate viewport real estate.
2. Force the dagre/ELK layout to leave large vertical gaps.
3. Make it hard to find a specific highlighted column when column-level lineage is selected — the user has to manually pan the DAG to find which row inside a tall card is highlighted.

## Goal

Cap the column list at ~15 rows of visible height. When the list exceeds this, make the list scrollable inside the card, and when a column-level lineage is selected, auto-scroll each affected card so the highlighted rows are visually centred.

Non-goals: virtualisation, column search, column filtering, header pinning. YAGNI — revisit only if perf or UX falls short.

## Key Codebase Facts (verified)

| Fact | Location |
|---|---|
| Column list is a plain `<ul><li>` with no overflow / max-height | [DbtModelNode.tsx:227-284](../../../frontend/src/components/DbtModelNode.tsx#L227-L284) |
| Column-level edges connect `source: ce.source_unique_id` → `target: ce.target_unique_id` (card UID, not row) | [App.tsx:683-697](../../../frontend/src/App.tsx#L683-L697) |
| Card has exactly two xyflow Handles (left target, right source) on the card edge | [DbtModelNode.tsx:113-132](../../../frontend/src/components/DbtModelNode.tsx#L113-L132) |
| `heights` memo computes per-model card height for layout; sums `colsH` per column with no cap | [App.tsx:518-541](../../../frontend/src/App.tsx#L518-L541) |
| Selecting a column auto-expands all models on the lineage trace | [App.tsx:444-455](../../../frontend/src/App.tsx#L444-L455) |
| Per-card `highlightedColumns: Set<string>` is already passed into `DbtModelNode` | [DbtModelNode.tsx:24](../../../frontend/src/components/DbtModelNode.tsx#L24) |

**Implication:** scrolling the inner `<ul>` does **not** misalign any edge — edges attach to the card frame, not to individual rows. Auto-scroll inside `<ul>` is purely a DOM-local effect.

## Design

### Trigger (threshold-based)

```
const COLUMN_SCROLL_THRESHOLD = 15;   // rows
```

If `data.columns.length > COLUMN_SCROLL_THRESHOLD`, the `<ul>` becomes height-capped and scrollable. Otherwise it renders exactly as today (no behaviour regression for small models).

### Max height value

Derived from threshold so the two stay in sync:

```
const COLUMN_ROW_HEIGHT_APPROX = 19;  // px — 3 + 13 + 3 (padding + line-height + padding)
const COLUMN_LIST_MAX_HEIGHT = COLUMN_SCROLL_THRESHOLD * COLUMN_ROW_HEIGHT_APPROX;
// = 285px
```

Single source of truth. If the threshold changes, the height adjusts automatically.

### DbtModelNode changes

1. Add `useRef<HTMLUListElement>` for the column list and tag each `<li>` with `data-highlighted="true"` when highlighted.
2. Conditional inline style on `<ul>`:
   - `maxHeight: COLUMN_LIST_MAX_HEIGHT` and `overflowY: "auto"` when `isScrollable`.
   - Untouched otherwise.
3. `useEffect` keyed on `data.highlightedColumns` size + content + `isScrollable`:
   - If not scrollable, or no highlights, do nothing.
   - Query `li[data-highlighted="true"]` inside the ref.
   - Measure `offsetTop` of the first and `offsetTop + offsetHeight` of the last.
   - Compute midpoint, scroll the `<ul>` so that midpoint lands at `clientHeight / 2`.
   - If the highlighted span is taller than `clientHeight`, fall back to scrolling the first highlighted row to the top (`block: "start"` semantics).
   - Use `behavior: "smooth"` for polish.

### App.tsx heights memo change

Compute `colsH` as today, then clamp:

```
if (m.columns.length > COLUMN_SCROLL_THRESHOLD) {
  colsH = Math.min(colsH, COLUMN_LIST_MAX_HEIGHT + COLS_VERTICAL_PADDING);
}
```

This keeps the dagre/ELK-layout-reported card height in sync with the DOM-capped height, so edges land where the card actually ends.

### Constants placement

Both `COLUMN_SCROLL_THRESHOLD` and `COLUMN_LIST_MAX_HEIGHT` belong with the existing layout constants in `App.tsx` (alongside `NODE_WIDTH`, `HEADER_BASE_HEIGHT`, etc.). Export them so `DbtModelNode` imports the same source of truth. Avoids drift between memo and DOM.

## Files changed

| File | Change | Est. LOC |
|---|---|---|
| `frontend/src/App.tsx` | Export 2 new constants; clamp `colsH` in `heights` memo | +8 |
| `frontend/src/components/DbtModelNode.tsx` | Import constants; add ref, `data-highlighted` attr, scroll `useEffect`, conditional max-height styles | +25 |
| **Total** | | **~33 LOC** |

No new dependencies. No Kotlin / sidecar changes. No `package.json` change.

## Edge cases & how they're handled

| Case | Handling |
|---|---|
| ≤ 15 columns | Falls through unchanged — `isScrollable = false` |
| Highlighted span > visible viewport | Fall back to scroll-to-top of first highlight |
| User selects column inside a tall card | Same card's own selected row is auto-centred by the same effect (selected column is implicitly in `highlightedColumns`) |
| Multiple cards on lineage trace | Each `DbtModelNode` runs its own effect independently — no cross-card coordination needed |
| Row heights vary (wrapping long column names / types) | Use `offsetTop` measurement, not `index × rowHeight` — naturally handles wraps |
| Scroll while user is mid-drag of DAG | xyflow's drag handler ignores inner scroll; isolated |
| Smooth scroll racing with re-render | Effect dependency includes `highlightedColumns` identity — React's batching collapses rapid changes |

## Risks (low)

1. **Estimated row height drift.** `COLUMN_ROW_HEIGHT_APPROX = 19` is an estimate. Wrapped columns are taller. The DOM scroll uses real `offsetTop`, so behaviour is correct; only the *visible row count* per 285px might be 13 or 14 instead of 15 for models with long column names. Acceptable — threshold is approximate by nature.
2. **Memo / DOM height mismatch on first paint.** `heights` memo's cap matches the DOM cap, so layout converges immediately. No flicker expected.
3. **Smooth-scroll on initial render.** First render of a newly-expanded card with highlights will trigger one smooth scroll. Acceptable polish, not jarring.

## Testing plan

Manual, in the running DAG view:
- [ ] Expand a model with <15 columns → unchanged behaviour, full list visible.
- [ ] Expand a model with >15 columns → list scrollable, card height stops at ~285px.
- [ ] Click a column in a small upstream model → downstream tall card auto-scrolls so the lineage-highlighted column is centred.
- [ ] Click a column near the bottom of a tall card → other affected cards (and the same card) scroll to centre the highlighted row.
- [ ] Click a column whose lineage hits 5+ models → every affected card with >15 columns centres its highlighted span.
- [ ] Edge case: lineage hits every column of a tall card → fallback to scroll-top behaviour (no infinite loop).
- [ ] Layout: verify dagre/ELK leaves no large vertical gap below a capped tall card.
- [ ] Edge visual: column-edge curves still terminate at card-frame handles, unaffected by inner scroll.

## Out of scope (for follow-ups)

- Column search / filter UI inside the card.
- Sticky column headers (none today — would need design).
- Configurable threshold per user preference.
- Virtualisation (only worth doing if columns × cards × DOM size becomes a perf bottleneck — not measured today).
