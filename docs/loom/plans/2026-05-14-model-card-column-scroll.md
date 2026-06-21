# Model Card Column Scroll Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Cap the expanded column `<ul>` inside a `DbtModelNode` at ~15 rows of visible height when columns.length > 15, with the inner list auto-scrolling to centre the highlighted column span when column-lineage is selected.

**Architecture:** Pure frontend change. Two new constants exported from `App.tsx` (threshold + max-height). `App.tsx`'s `heights` memo clamps `colsH` to match the DOM cap, so dagre/ELK layout converges. `DbtModelNode.tsx` gets a ref on the `<ul>`, `data-highlighted` attrs on `<li>`s, conditional `maxHeight`/`overflowY` styles, and a `useEffect` that calls a pure helper (`computeScrollTopForCentering`) and applies the result via `ul.scrollTo`. The pure helper lives in `frontend/src/lib/column-scroll.ts` and is the only piece with a unit test, matching the project's existing Tier-1-only test discipline.

**Tech Stack:** React 19, `@xyflow/react` 12, TypeScript, vitest. No new dependencies.

**Spec:** `docs/loom/specs/2026-05-14-model-card-column-scroll-design.md`

**Testing scope:** Per project memory (`Test coverage scope — Tier 2/3 deferred`), no React/JCEF/Swing/RTL/dagre layer tests. Only the pure scroll-math helper gets a vitest unit test. All UI behaviour validated manually per the spec's test plan.

---

## File Structure

| File | Action | Responsibility |
|---|---|---|
| `frontend/src/App.tsx` | Modify | Export 2 new constants; clamp `colsH` in `heights` memo |
| `frontend/src/lib/column-scroll.ts` | Create | Pure helper `computeScrollTopForCentering` |
| `frontend/src/lib/column-scroll.test.ts` | Create | Vitest unit tests for the helper |
| `frontend/src/components/DbtModelNode.tsx` | Modify | Add ref, `data-highlighted`, conditional styles, scroll effect using helper |

---

### Task 1: Add scroll-math constants & clamp the heights memo

**Files:**
- Modify: `frontend/src/App.tsx` (constants block near the top; `heights` memo at L518-541)

- [ ] **Step 1: Locate the existing layout constants block**

Open `frontend/src/App.tsx`. Search for `NODE_WIDTH`. The constants live near the top (around L32 onwards) — `NODE_WIDTH`, `CHARS_PER_NAME_LINE`, `HEADER_BASE_HEIGHT`, `NAME_LINE_HEIGHT`, `COLUMN_NAME_CHARS_PER_LINE`, `COLUMN_TYPE_CHARS_PER_LINE`, `COLUMN_LINE_HEIGHT`, `COLUMN_ROW_PADDING`, `COLS_VERTICAL_PADDING`.

- [ ] **Step 2: Add two new exported constants**

Add immediately after the existing column-related constants:

```ts
/**
 * When a model has more than this many columns, the expanded column list
 * becomes height-capped and scrollable inside the card. Models at or below
 * this threshold render as today, with no overflow handling.
 */
export const COLUMN_SCROLL_THRESHOLD = 15;

/**
 * Visible height (px) of the column list when scrolling kicks in. Derived
 * from the threshold so they stay in sync; one source of truth lets the
 * `heights` memo and `DbtModelNode`'s inline style agree.
 *
 * Approximation: 3px top padding + 13px line-height + 3px bottom padding
 * = 19px per row. Wrapped rows are taller, so this is the row-count cap
 * for the typical unwrapped case.
 */
export const COLUMN_LIST_MAX_HEIGHT = COLUMN_SCROLL_THRESHOLD * 19;
```

- [ ] **Step 3: Clamp `colsH` inside the heights memo**

Locate the `heights` memo (currently L518-541). Modify the body of the `for (const m of payload.models)` loop. After the existing `colsH += COLS_VERTICAL_PADDING;` line (currently L536), add the clamp:

```ts
        colsH += COLS_VERTICAL_PADDING;
        if (m.columns.length > COLUMN_SCROLL_THRESHOLD) {
          colsH = Math.min(colsH, COLUMN_LIST_MAX_HEIGHT + COLS_VERTICAL_PADDING);
        }
      }
      h[m.unique_id] = headerH + colsH;
```

This keeps `heights[unique_id]` (used by dagre/ELK) in sync with the DOM cap applied by `DbtModelNode`.

- [ ] **Step 4: Build & lint**

Run from `frontend/`:
```bash
pnpm exec tsc -b
pnpm lint
```
Expected: no errors. The new constants are unused by other files yet — that's fine; they'll be consumed in Task 4.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/App.tsx
git commit -m "feat(layout): clamp expanded column height in heights memo for tall models"
```

---

### Task 2: Pure scroll-math helper — write the failing test

**Files:**
- Create: `frontend/src/lib/column-scroll.test.ts`

- [ ] **Step 1: Write the test file**

Create `frontend/src/lib/column-scroll.test.ts`:

```ts
import { describe, expect, it } from "vitest";
import { computeScrollTopForCentering } from "./column-scroll";

/**
 * `computeScrollTopForCentering` is the pure math behind the column
 * list's auto-scroll. Given the geometry of the highlighted span and
 * the scroll viewport, it returns the `scrollTop` that centres the
 * span vertically — or, when the span is taller than the viewport,
 * falls back to scroll-to-top-of-first so the user at least sees the
 * start of the highlighted region.
 */
describe("computeScrollTopForCentering", () => {
  it("centres a small highlighted span inside the viewport", () => {
    // Highlight 100-150 inside a 0-285 viewport, scroll content height 600.
    // midpoint = 125, viewport half = 142.5 → scrollTop = 125 - 142.5 = -17.5
    // clamped to [0, scrollHeight - clientHeight] = [0, 315] → 0
    expect(
      computeScrollTopForCentering({
        firstTop: 100,
        lastBottom: 150,
        clientHeight: 285,
        scrollHeight: 600,
      }),
    ).toBe(0);
  });

  it("centres a span that sits in the middle of a long list", () => {
    // Highlight 400-450 inside 285-tall viewport, scrollHeight 1000.
    // midpoint = 425, viewport half = 142.5 → scrollTop = 282.5
    // clamped to [0, 715] → 282.5
    expect(
      computeScrollTopForCentering({
        firstTop: 400,
        lastBottom: 450,
        clientHeight: 285,
        scrollHeight: 1000,
      }),
    ).toBe(282.5);
  });

  it("clamps to max scrollable when the span is near the bottom", () => {
    // Highlight 950-980 inside 285-tall viewport, scrollHeight 1000.
    // midpoint = 965, viewport half = 142.5 → scrollTop = 822.5
    // clamped to [0, scrollHeight - clientHeight] = [0, 715] → 715
    expect(
      computeScrollTopForCentering({
        firstTop: 950,
        lastBottom: 980,
        clientHeight: 285,
        scrollHeight: 1000,
      }),
    ).toBe(715);
  });

  it("falls back to scroll-first-highlight-to-top when span is taller than viewport", () => {
    // Highlight 100-500 (span 400) inside 285-tall viewport.
    // Span > viewport → return firstTop so the user sees the start.
    expect(
      computeScrollTopForCentering({
        firstTop: 100,
        lastBottom: 500,
        clientHeight: 285,
        scrollHeight: 1000,
      }),
    ).toBe(100);
  });

  it("clamps fallback to max scrollable when first highlight is near the bottom", () => {
    // Even in fallback, scrollTop must respect [0, scrollHeight - clientHeight].
    expect(
      computeScrollTopForCentering({
        firstTop: 950,
        lastBottom: 1500, // tall span
        clientHeight: 285,
        scrollHeight: 1000,
      }),
    ).toBe(715);
  });

  it("returns 0 when the entire content fits inside the viewport", () => {
    // scrollHeight <= clientHeight → no scrolling possible.
    expect(
      computeScrollTopForCentering({
        firstTop: 50,
        lastBottom: 80,
        clientHeight: 285,
        scrollHeight: 285,
      }),
    ).toBe(0);
  });
});
```

- [ ] **Step 2: Run the test to confirm it fails**

Run from `frontend/`:
```bash
pnpm exec vitest run src/lib/column-scroll.test.ts
```

Expected: FAIL — `Cannot find module './column-scroll'` (helper not yet created).

---

### Task 3: Pure scroll-math helper — implement to pass

**Files:**
- Create: `frontend/src/lib/column-scroll.ts`

- [ ] **Step 1: Write the helper**

Create `frontend/src/lib/column-scroll.ts`:

```ts
/**
 * Geometry inputs for {@link computeScrollTopForCentering}.
 *
 * All values are pixel coordinates relative to the scrollable container:
 * - `firstTop`, `lastBottom` describe the bounding box of the highlighted
 *   span (first highlighted row's `offsetTop`, last highlighted row's
 *   `offsetTop + offsetHeight`).
 * - `clientHeight` is the visible height of the scroll viewport.
 * - `scrollHeight` is the total scrollable content height.
 */
export type ScrollCenteringInputs = {
  firstTop: number;
  lastBottom: number;
  clientHeight: number;
  scrollHeight: number;
};

/**
 * Compute the `scrollTop` value that centres a highlighted span inside
 * a scroll viewport. When the span is taller than the viewport, fall
 * back to scrolling the first highlighted row to the top of the viewport
 * (the user sees the start of the region rather than nothing).
 *
 * The returned value is always clamped to `[0, scrollHeight - clientHeight]`
 * so the caller can pass it to `element.scrollTo({ top, behavior: ... })`
 * without further bounds checks.
 */
export function computeScrollTopForCentering(
  inputs: ScrollCenteringInputs,
): number {
  const { firstTop, lastBottom, clientHeight, scrollHeight } = inputs;
  const maxScrollTop = Math.max(0, scrollHeight - clientHeight);

  const spanHeight = lastBottom - firstTop;
  if (spanHeight > clientHeight) {
    // Span doesn't fit — show the start of the highlighted region.
    return Math.max(0, Math.min(maxScrollTop, firstTop));
  }

  const midpoint = (firstTop + lastBottom) / 2;
  const desired = midpoint - clientHeight / 2;
  return Math.max(0, Math.min(maxScrollTop, desired));
}
```

- [ ] **Step 2: Run the test to confirm it passes**

```bash
pnpm exec vitest run src/lib/column-scroll.test.ts
```

Expected: PASS, all 6 tests green.

- [ ] **Step 3: Run the full test suite & build**

```bash
pnpm exec vitest run
pnpm exec tsc -b
pnpm lint
```

Expected: all tests pass, no type errors, no lint errors.

- [ ] **Step 4: Commit**

```bash
git add frontend/src/lib/column-scroll.ts frontend/src/lib/column-scroll.test.ts
git commit -m "feat(scroll): pure helper to centre highlighted column span"
```

---

### Task 4: DbtModelNode — apply max-height & overflow conditionally

**Files:**
- Modify: `frontend/src/components/DbtModelNode.tsx` (column `<ul>` block at L227-284)

- [ ] **Step 1: Import the constants and a ref hook**

In `DbtModelNode.tsx`, locate the existing import lines near the top. Add:

```ts
import { useEffect, useRef, useState } from "react";
import { COLUMN_LIST_MAX_HEIGHT, COLUMN_SCROLL_THRESHOLD } from "../App";
```

(If `useState` is already imported, keep it — just add `useEffect` and `useRef` alongside.)

If `useState` is currently imported from `"react"` without `useRef`/`useEffect`, merge them into the existing import line instead of adding a duplicate.

- [ ] **Step 2: Add the ref and the `isScrollable` flag inside the component**

Find the body of the `DbtModelNode` component (the one rendering the card; starts around L105 with `return ( <div ... `). At the top of the function body (before the `return`), alongside the existing `const [hover, setHover]` line, add:

```ts
const columnListRef = useRef<HTMLUListElement>(null);
const isScrollable = data.columns.length > COLUMN_SCROLL_THRESHOLD;
```

- [ ] **Step 3: Wire the `<ul>` ref, conditional styles, and `data-highlighted` attr**

Locate the existing column list block (currently L227-284). Replace the `<ul>` open tag and the `<li>` open tag as follows.

Existing (L228):
```tsx
<ul style={{ listStyle: "none", margin: 0, padding: "4px 0" }}>
```
Replace with:
```tsx
<ul
  ref={columnListRef}
  style={{
    listStyle: "none",
    margin: 0,
    padding: "4px 0",
    maxHeight: isScrollable ? COLUMN_LIST_MAX_HEIGHT : undefined,
    overflowY: isScrollable ? "auto" : "visible",
  }}
>
```

Existing (L232):
```tsx
<li
  key={col.name}
  onClick={(e) => {
```
Replace with:
```tsx
<li
  key={col.name}
  data-highlighted={highlighted ? "true" : undefined}
  onClick={(e) => {
```

- [ ] **Step 4: Build, lint, run tests**

From `frontend/`:
```bash
pnpm exec tsc -b
pnpm lint
pnpm exec vitest run
```

Expected: no errors, all existing tests still pass.

- [ ] **Step 5: Manual smoke test — small model unchanged**

Start the dev server and open the DAG against a project that has at least one model with ≤ 15 columns:
```bash
pnpm dev
```

Visually confirm:
- A small model (≤ 15 columns) expanded → column list looks identical to before this change, no scroll bar.

(Skip this step if the dev server can't be run standalone outside the IntelliJ JCEF host — in that case, defer the smoke test to Task 6 where the full plugin is rebuilt.)

- [ ] **Step 6: Manual smoke test — large model scrolls**

In the same dev session, expand a model with > 15 columns. Confirm:
- The column list is height-capped (~285px).
- A vertical scroll bar appears inside the card.
- Manual scrolling of the inner list works (mouse wheel inside the list, drag the scroll bar).
- The card's overall height in the DAG is now bounded — no longer dwarfs neighbours.
- Column-level edges (when a column is selected) still terminate at the card frame's left/right handles, not at column rows.

- [ ] **Step 7: Commit**

```bash
git add frontend/src/components/DbtModelNode.tsx
git commit -m "feat(card): height-cap and inner-scroll for tall column lists"
```

---

### Task 5: DbtModelNode — auto-scroll highlighted span on column selection

**Files:**
- Modify: `frontend/src/components/DbtModelNode.tsx`

- [ ] **Step 1: Import the scroll helper**

Add to the import block:

```ts
import { computeScrollTopForCentering } from "../lib/column-scroll";
```

- [ ] **Step 2: Add the auto-scroll effect inside the component**

Inside `DbtModelNode`, after the `const isScrollable = ...` line from Task 4, add:

```ts
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
```

Notes for the engineer:
- `data.highlightedColumns` is the Set already passed in via props (see L24 of `DbtModelNode.tsx`). React re-renders when the identity changes — when `App.tsx` recomputes the lineage trace, a new Set is passed in, triggering the effect.
- Including `data.expanded` in the deps catches the case where a card was selected while collapsed and is then expanded — the effect runs on the re-render after expand.
- `behavior: "smooth"` gives polish; if it feels jarring during testing, change to `"auto"`.

- [ ] **Step 3: Build, lint, run tests**

```bash
pnpm exec tsc -b
pnpm lint
pnpm exec vitest run
```

Expected: no errors, all tests pass.

- [ ] **Step 4: Manual verification — auto-scroll on selection**

Run the plugin (rebuild and reload — full IntelliJ run config, not just `pnpm dev`, since column lineage involves the Kotlin side fetching `column_edges`):

```bash
# from project root
./gradlew runIde
```

In the running IDE, open a dbt project with column-lineage data and exercise these scenarios from the spec test plan:

- [ ] Click a column in a small upstream model. A downstream tall card auto-expands AND auto-scrolls so the highlighted column is centred.
- [ ] Click a column near the bottom of a tall card. The same card scrolls so that column is visible AND other affected cards centre their highlighted rows.
- [ ] Click a column whose lineage hits 5+ models. Every affected card with > 15 columns centres its highlighted span.
- [ ] Lineage that hits every column of a tall card (e.g. SELECT *). The fallback kicks in — scrolls to the top of the first highlight, no infinite-loop / jitter.
- [ ] Re-click the same column (toggle off → on). Scroll behaves correctly on the second selection.
- [ ] Layout check: no large vertical gap below a height-capped card (dagre/ELK is reading the clamped height).
- [ ] Edge visual: column-edge curves still attach to card-frame handles (left/right), unaffected by inner scroll.

If any scenario misbehaves, capture the symptom before reaching for a fix — the effect-deps array, the `data-highlighted` selector, and the helper math are the three places to check.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/components/DbtModelNode.tsx
git commit -m "feat(card): auto-scroll to centre highlighted column span on lineage select"
```

---

### Task 6: Final verification & PR

- [ ] **Step 1: Run the full pipeline once more**

```bash
cd frontend
pnpm exec tsc -b
pnpm lint
pnpm exec vitest run
pnpm build
```

Expected: green across the board.

- [ ] **Step 2: Walk through the spec's full test plan**

From `docs/loom/specs/2026-05-14-model-card-column-scroll-design.md`, run each checkbox in the **Testing plan** section against the running plugin. Mark off each one. If any fail, file a follow-up commit before opening the PR.

- [ ] **Step 3: Push and open PR**

```bash
git push -u origin feat/model-card-column-scroll
```

Then open the PR (gh pr create with a brief summary linking the spec and listing the manually-verified test scenarios). Use the commit-commands:commit-push-pr skill if available, otherwise open via `gh pr create`.

---

## Spec Coverage Check

| Spec section | Tasks covering it |
|---|---|
| Trigger (`columns.length > 15`) | Task 1 (constant), Task 4 (`isScrollable`) |
| Max height derivation from threshold | Task 1 (constant) |
| `<ul>` max-height + overflow-y | Task 4 |
| `data-highlighted` attr | Task 4 |
| Pure scroll helper with fallback | Tasks 2, 3 |
| `useEffect` keyed on `highlightedColumns` | Task 5 |
| `heights` memo clamp | Task 1 |
| Constants shared between App.tsx & DbtModelNode | Task 1 export + Task 4 import |
| Manual test plan from spec | Tasks 4 step 5-6 + Task 5 step 4 + Task 6 step 2 |

No gaps.
