# ELK Migration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace dagre with elkjs in the frontend layout module so that high-fan-in/out hub models can be laid out with `layerUnzipping`, while preserving existing behavior on non-hub graphs.

**Architecture:** Parallel implementation. Write `layout-elk.ts` alongside the existing dagre module, dispatch via an internal const flag during validation, then cut over by deleting the dagre module. App.tsx splits the current single sync `useMemo` (which calls dagre) into three pieces (rawNodes useMemo, positions useState+useEffect, merged derivedNodes useMemo) because elkjs returns a Promise and useMemo cannot.

**Tech Stack:** elkjs ^0.11 (drops dagre), React 19, @xyflow/react 12, vitest 4, TypeScript 6, pnpm 10.30.

**Source spec:** [2026-05-10-elk-migration-design.md](../specs/2026-05-10-elk-migration-design.md)

**Commit convention:** Each commit message must end with the trailer
`Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>`
(per repo history). The HEREDOC commit examples below omit it for brevity —
add it before `EOF` when executing.

---

## File Structure

| File | Action | Purpose |
|---|---|---|
| [frontend/package.json](../../../frontend/package.json) | Modify | Add `elkjs`; remove `dagre` + `@types/dagre` at cutover |
| [frontend/src/lib/layout.ts](../../../frontend/src/lib/layout.ts) | Modify (rename → dispatcher → delete) | Renamed to `layout-dagre.ts`, then a new dispatcher takes its place, then removed at cutover |
| `frontend/src/lib/layout-dagre.ts` | Create (rename from layout.ts) | Existing dagre impl — kept for parallel A/B until cutover |
| `frontend/src/lib/layout-elk.ts` | Create | New async ELK-based layout function — same `LayoutOptions` shape, returns `Promise<Node[]>` |
| `frontend/src/lib/layout-elk.test.ts` | Create | vitest unit tests |
| [frontend/src/App.tsx](../../../frontend/src/App.tsx) | Modify (lines 392-456) | Split `derivedNodes` useMemo into rawNodes useMemo + positions useState+useEffect + merged useMemo |

---

## Task 0: JCEF compatibility spike

**Files:**
- Modify: `frontend/package.json`
- Create (throwaway): `frontend/src/lib/elk-spike.ts`
- Modify (temporary): `frontend/src/main.tsx`

This task is exploratory, not TDD. Goal: verify elkjs runs at all in the JCEF webview before investing in the migration.

- [ ] **Step 1: Add elkjs dependency**

```bash
cd frontend && pnpm add elkjs@^0.11
```

Expected: `package.json` gains `"elkjs": "^0.11.x"` under `dependencies`.

- [ ] **Step 2: Create spike script**

Create `frontend/src/lib/elk-spike.ts`:

```typescript
import ELK from "elkjs/lib/elk.bundled.js";

export async function runSpike(): Promise<void> {
  const elk = new ELK();
  const graph = {
    id: "root",
    layoutOptions: { "elk.algorithm": "layered", "elk.direction": "RIGHT" },
    children: [
      { id: "a", width: 100, height: 50 },
      { id: "b", width: 100, height: 50 },
      { id: "c", width: 100, height: 50 },
      { id: "d", width: 100, height: 50 },
      { id: "e", width: 100, height: 50 },
    ],
    edges: [
      { id: "ab", sources: ["a"], targets: ["b"] },
      { id: "bc", sources: ["b"], targets: ["c"] },
      { id: "cd", sources: ["c"], targets: ["d"] },
      { id: "de", sources: ["d"], targets: ["e"] },
    ],
  };
  const result = await elk.layout(graph);
  console.log("[elk-spike] result", JSON.stringify(result, null, 2));
}
```

- [ ] **Step 3: Wire spike into main.tsx temporarily**

In `frontend/src/main.tsx`, add a one-line import + call near the top:

```typescript
import { runSpike } from "./lib/elk-spike";
runSpike().catch((e) => console.error("[elk-spike] FAILED", e));
```

- [ ] **Step 4: Run sandbox IDE and check console**

```bash
cd plugin && ./gradlew runIde -Pide.project=/Users/kouko/DataspellProjects/iCHEF-dbt-pipeline
```

Open the dbtree tool window. Expected JCEF console output:
- `[elk-spike] result {...}` with `x`, `y` coordinates filled in for nodes a-e
- No errors about Web Worker, blob: URL, CSP, or `Worker is not defined`

If any error appears, stop and capture the exact message — it determines whether to proceed with the bundled worker or switch to inline / sync mode in Task 4.

- [ ] **Step 5: Revert spike, keep dependency**

Remove the spike import + call from `main.tsx`. Delete `frontend/src/lib/elk-spike.ts`. Keep `elkjs` in package.json.

- [ ] **Step 6: Commit**

```bash
git add frontend/package.json frontend/pnpm-lock.yaml
git commit -m "$(cat <<'EOF'
build(frontend): add elkjs dependency

JCEF compatibility verified via throwaway spike (a 5-node chain laid out
through elkjs/lib/elk.bundled.js with no worker/blob URL errors). Spike
artefacts removed; only the dependency remains.
EOF
)"
```

---

## Task 1: TDD cycle — empty graph

**Files:**
- Create: `frontend/src/lib/layout-elk.ts`
- Create: `frontend/src/lib/layout-elk.test.ts`

- [ ] **Step 1: Write the failing test**

Create `frontend/src/lib/layout-elk.test.ts`:

```typescript
import { describe, it, expect } from "vitest";
import { layoutModelGraph } from "./layout-elk";

describe("layoutModelGraph (elk)", () => {
  it("returns empty array for empty input", async () => {
    const result = await layoutModelGraph([], [], {
      nodeWidth: 200,
      nodesepX: 60,
      ranksepY: 100,
      heights: {},
    });
    expect(result).toEqual([]);
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd frontend && pnpm test layout-elk
```

Expected: FAIL with module-not-found or `layoutModelGraph is not a function`.

- [ ] **Step 3: Implement minimal layout-elk.ts**

Create `frontend/src/lib/layout-elk.ts`:

```typescript
import ELK from "elkjs/lib/elk.bundled.js";
import type { Edge, Node } from "@xyflow/react";

export interface LayoutOptions {
  rankdir?: "LR" | "TB";
  nodeWidth: number;
  nodesepX: number;
  ranksepY: number;
  heights: Record<string, number>;
}

const elk = new ELK();

export async function layoutModelGraph(
  nodes: Node[],
  edges: Edge[],
  opts: LayoutOptions,
): Promise<Node[]> {
  if (nodes.length === 0) return [];

  const direction = (opts.rankdir ?? "LR") === "LR" ? "RIGHT" : "DOWN";
  const graph = {
    id: "root",
    layoutOptions: {
      "elk.algorithm": "layered",
      "elk.direction": direction,
      "elk.layered.spacing.nodeNodeBetweenLayers": String(opts.ranksepY),
      "elk.spacing.nodeNode": String(opts.nodesepX),
    },
    children: nodes.map((n) => ({
      id: n.id,
      width: opts.nodeWidth,
      height: opts.heights[n.id] ?? 60,
    })),
    edges: edges.map((e) => ({
      id: e.id,
      sources: [e.source],
      targets: [e.target],
    })),
  };

  const result = await elk.layout(graph);
  const childById = new Map((result.children ?? []).map((c) => [c.id, c]));

  return nodes.map((n) => {
    const c = childById.get(n.id);
    const height = opts.heights[n.id] ?? 60;
    return {
      ...n,
      position: { x: c?.x ?? 0, y: c?.y ?? 0 },
      width: opts.nodeWidth,
      height,
    };
  });
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
cd frontend && pnpm test layout-elk
```

Expected: PASS — 1 test.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/lib/layout-elk.ts frontend/src/lib/layout-elk.test.ts
git commit -m "$(cat <<'EOF'
feat(frontend): scaffold layout-elk.ts (empty-graph case)

Mirror the LayoutOptions shape of the dagre module but make the entry
function async because elkjs returns a Promise. Empty input returns []
without invoking ELK.
EOF
)"
```

---

## Task 2: TDD cycle — linear chain x ordering

**Files:**
- Modify: `frontend/src/lib/layout-elk.test.ts`

- [ ] **Step 1: Write the failing test**

Append to `frontend/src/lib/layout-elk.test.ts`:

```typescript
import type { Node, Edge } from "@xyflow/react";

function makeNode(id: string): Node {
  return { id, type: "default", position: { x: 0, y: 0 }, data: {} };
}

function makeEdge(id: string, source: string, target: string): Edge {
  return { id, source, target };
}

describe("layoutModelGraph (elk) — linear chain", () => {
  it("places nodes in monotonic increasing x for LR direction", async () => {
    const ids = ["a", "b", "c", "d", "e"];
    const nodes = ids.map(makeNode);
    const edges = [
      makeEdge("ab", "a", "b"),
      makeEdge("bc", "b", "c"),
      makeEdge("cd", "c", "d"),
      makeEdge("de", "d", "e"),
    ];
    const heights = Object.fromEntries(ids.map((id) => [id, 60]));

    const result = await layoutModelGraph(nodes, edges, {
      rankdir: "LR",
      nodeWidth: 200,
      nodesepX: 60,
      ranksepY: 100,
      heights,
    });

    const byId = new Map(result.map((r) => [r.id, r.position.x]));
    expect(byId.get("a")! < byId.get("b")!).toBe(true);
    expect(byId.get("b")! < byId.get("c")!).toBe(true);
    expect(byId.get("c")! < byId.get("d")!).toBe(true);
    expect(byId.get("d")! < byId.get("e")!).toBe(true);
  });
});
```

- [ ] **Step 2: Run test to verify it passes**

```bash
cd frontend && pnpm test layout-elk
```

The implementation already supports this (it's just a different input). Expected: PASS — 2 tests.

If it fails: check the ELK output coordinates by adding a `console.log(result)` in the test temporarily.

- [ ] **Step 3: Commit**

```bash
git add frontend/src/lib/layout-elk.test.ts
git commit -m "$(cat <<'EOF'
test(frontend): linear-chain x ordering for layout-elk

Verify ELK arranges a 5-node chain in monotonic x order under LR direction.
EOF
)"
```

---

## Task 3: TDD cycle — variable height nodes do not overlap

**Files:**
- Modify: `frontend/src/lib/layout-elk.test.ts`

- [ ] **Step 1: Write the failing test**

Append to `frontend/src/lib/layout-elk.test.ts`:

```typescript
describe("layoutModelGraph (elk) — variable heights", () => {
  it("does not overlap two siblings with different heights in same layer", async () => {
    // Three sources all flowing into one sink — the three sources sit in
    // the same x-rank and need vertical separation.
    const nodes = [makeNode("s1"), makeNode("s2"), makeNode("s3"), makeNode("sink")];
    const edges = [
      makeEdge("e1", "s1", "sink"),
      makeEdge("e2", "s2", "sink"),
      makeEdge("e3", "s3", "sink"),
    ];
    const heights = { s1: 60, s2: 200, s3: 60, sink: 60 };

    const result = await layoutModelGraph(nodes, edges, {
      rankdir: "LR",
      nodeWidth: 200,
      nodesepX: 60,
      ranksepY: 100,
      heights,
    });

    const byId = new Map(result.map((r) => [r.id, r]));
    const s1 = byId.get("s1")!;
    const s2 = byId.get("s2")!;
    const s3 = byId.get("s3")!;

    const yIntervals = [
      { id: "s1", top: s1.position.y, bottom: s1.position.y + 60 },
      { id: "s2", top: s2.position.y, bottom: s2.position.y + 200 },
      { id: "s3", top: s3.position.y, bottom: s3.position.y + 60 },
    ].sort((a, b) => a.top - b.top);

    for (let i = 0; i < yIntervals.length - 1; i++) {
      expect(yIntervals[i].bottom).toBeLessThanOrEqual(yIntervals[i + 1].top);
    }
  });
});
```

- [ ] **Step 2: Run test to verify it passes**

```bash
cd frontend && pnpm test layout-elk
```

Expected: PASS — 3 tests. If overlap occurs, ELK is mishandling the heights — inspect the request payload.

- [ ] **Step 3: Commit**

```bash
git add frontend/src/lib/layout-elk.test.ts
git commit -m "$(cat <<'EOF'
test(frontend): variable-height non-overlap for layout-elk

Three sources of differing heights flowing into one sink must not
overlap on the y-axis, regardless of which one is tallest.
EOF
)"
```

---

## Task 4: Hub-case test (placeholder, becomes real in Task 11)

**Files:**
- Modify: `frontend/src/lib/layout-elk.test.ts`

- [ ] **Step 1: Add `it.todo` placeholder**

Append to `frontend/src/lib/layout-elk.test.ts`:

```typescript
describe("layoutModelGraph (elk) — hub case", () => {
  it.todo(
    "uses layerUnzipping to spread 30 fan-in siblings across multiple sub-columns",
  );
});
```

- [ ] **Step 2: Run tests to confirm `todo` is reported, not failing**

```bash
cd frontend && pnpm test layout-elk
```

Expected: 3 passing + 1 todo (vitest reports `todo` as a yellow line, not a failure).

- [ ] **Step 3: Commit**

```bash
git add frontend/src/lib/layout-elk.test.ts
git commit -m "$(cat <<'EOF'
test(frontend): pencil in hub-case TODO for layout-elk

Placeholder for the layerUnzipping assertion; will be promoted to a
real assertion once Task 11 enables ALTERNATING strategy.
EOF
)"
```

---

## Task 5: Refactor App.tsx to async-layout state shape (still using dagre)

**Files:**
- Modify: [frontend/src/lib/layout.ts](../../../frontend/src/lib/layout.ts)
- Modify: [frontend/src/App.tsx](../../../frontend/src/App.tsx) lines 17-18 and 392-456

The current `layoutModelGraph` is sync. We make it return `Promise<Node[]>` (wrapping the dagre result in `Promise.resolve`) so that the App.tsx call site can be refactored to the async pattern *before* introducing ELK. This decouples "state-shape refactor" from "engine swap" — easier to bisect if something breaks.

- [ ] **Step 1: Make existing layout.ts return a Promise**

Modify [frontend/src/lib/layout.ts](../../../frontend/src/lib/layout.ts) — change the function signature and final return:

```typescript
export async function layoutModelGraph(
  nodes: Node[],
  edges: Edge[],
  opts: LayoutOptions,
): Promise<Node[]> {
  const g = new dagre.graphlib.Graph();
  g.setGraph({
    rankdir: opts.rankdir ?? "LR",
    nodesep: opts.nodesepX,
    ranksep: opts.ranksepY,
    marginx: 24,
    marginy: 24,
  });
  g.setDefaultEdgeLabel(() => ({}));

  for (const n of nodes) {
    const height = opts.heights[n.id] ?? 60;
    g.setNode(n.id, { width: opts.nodeWidth, height });
  }

  for (const e of edges) {
    g.setEdge(e.source, e.target);
  }

  dagre.layout(g);

  return nodes.map((n) => {
    const pos = g.node(n.id);
    return {
      ...n,
      position: { x: pos.x - opts.nodeWidth / 2, y: pos.y - pos.height / 2 },
      width: opts.nodeWidth,
      height: pos.height,
    };
  });
}
```

(Only `function` → `async function` and return type `Node[]` → `Promise<Node[]>` change.)

- [ ] **Step 2: Refactor App.tsx — split derivedNodes**

Modify [frontend/src/App.tsx](../../../frontend/src/App.tsx).

First, add `useEffect`, `useState` to the React imports if not already present (check existing imports near top of file).

Then replace the entire `derivedNodes` useMemo block (currently lines 392-456) with:

```typescript
// ---- xyflow nodes/edges --------------------------------------------------
// Layout runs asynchronously, so node data and node positions are computed
// in two phases:
//   rawNodes:  pure data (sync, useMemo)
//   positions: layout output (async, useEffect → useState)
//   derivedNodes: rawNodes merged with positions and manualPositions (sync, useMemo)

const rawNodes: Array<Node<DbtModelNodeData, "dbtModel">> = useMemo(() => {
  const isExpanded = (uid: string) => expanded.has(uid);

  const onLineagePath = (uid: string) =>
    selectedColumn ? lineageTrace.models.has(uid) : modelTrace.all.has(uid);

  return payload.models.map((m) => ({
    id: m.unique_id,
    type: "dbtModel" as const,
    position: { x: 0, y: 0 },
    data: {
      unique_id: m.unique_id,
      name: m.name,
      package_name: m.package_name,
      layer: m.layer,
      folder: m.folder,
      materialization: m.materialization,
      columns: m.columns,
      expanded: isExpanded(m.unique_id),
      columnsPending: pendingColumns.has(m.unique_id),
      highlightedColumns: lineageTrace.columns.get(m.unique_id) ?? new Set<string>(),
      onLineagePath: onLineagePath(m.unique_id),
      isSelectedModel: m.unique_id === selectedModelUid,
      theme,
      cardWidth: NODE_WIDTH,
      onToggleExpanded: toggleExpanded,
      onColumnClick,
      onOpenFile,
    },
  }));
}, [
  payload,
  expanded,
  pendingColumns,
  lineageTrace,
  modelTrace,
  selectedColumn,
  theme,
  selectedModelUid,
  toggleExpanded,
  onColumnClick,
  onOpenFile,
]);

const heights: Record<string, number> = useMemo(() => {
  const h: Record<string, number> = {};
  for (const m of payload.models) {
    const nameLines = Math.max(1, Math.ceil(m.name.length / CHARS_PER_NAME_LINE));
    const headerH = HEADER_BASE_HEIGHT + (nameLines - 1) * NAME_LINE_HEIGHT;
    const colsH = expanded.has(m.unique_id)
      ? m.columns.length * ROW_HEIGHT + COLS_VERTICAL_PADDING
      : 0;
    h[m.unique_id] = headerH + colsH;
  }
  return h;
}, [payload, expanded]);

const [positions, setPositions] = useState<Record<string, { x: number; y: number }>>({});

useEffect(() => {
  let cancelled = false;
  layoutModelGraph(rawNodes, modelLevelEdges(payload), {
    rankdir: "LR",
    nodeWidth: NODE_WIDTH,
    nodesepX: 60,
    ranksepY: 100,
    heights,
  }).then((positioned) => {
    if (cancelled) return;
    const next: Record<string, { x: number; y: number }> = {};
    for (const n of positioned) next[n.id] = n.position;
    setPositions(next);
  });
  return () => {
    cancelled = true;
  };
}, [rawNodes, heights, payload]);

const derivedNodes: Node[] = useMemo(() => {
  return rawNodes.map((n) => {
    const manual = manualPositions[n.id];
    const auto = positions[n.id];
    return {
      ...n,
      position: manual ?? auto ?? { x: 0, y: 0 },
      width: NODE_WIDTH,
      height: heights[n.id] ?? 60,
    };
  });
}, [rawNodes, positions, manualPositions, heights]);
```

- [ ] **Step 3: Run typecheck and existing tests**

```bash
cd frontend && pnpm run build && pnpm test
```

Expected: TypeScript clean, all existing tests still pass (DbtModelNode.test.ts, HopStepper.test.ts, lineage-trace.test.ts, theme.test.ts, layout-elk.test.ts).

- [ ] **Step 4: Manual sandbox verification**

```bash
cd plugin && ./gradlew runIde -Pide.project=/Users/kouko/DataspellProjects/iCHEF-dbt-pipeline
```

Open dbtree, open a model, change hop, expand columns, drag a node. All four interactions should behave identically to before. There may be a one-frame flicker at hop change because positions arrive on the next tick — note it but do not fix yet (Task 9 will refine).

- [ ] **Step 5: Commit**

```bash
git add frontend/src/lib/layout.ts frontend/src/App.tsx
git commit -m "$(cat <<'EOF'
refactor(frontend): split derivedNodes into rawNodes + positions + merge

Prepare App.tsx for an async layout engine by separating sync node-data
construction from async layout-position resolution. layoutModelGraph
now returns Promise<Node[]>, and App.tsx pipes the result through
useState/useEffect so that useMemo no longer needs to call layout.

Currently still uses dagre under the hood; behavior unchanged.
EOF
)"
```

---

## Task 6: Engine flag dispatcher

**Files:**
- Rename: [frontend/src/lib/layout.ts](../../../frontend/src/lib/layout.ts) → `frontend/src/lib/layout-dagre.ts`
- Create: `frontend/src/lib/layout.ts` (new dispatcher)

- [ ] **Step 1: Rename layout.ts to layout-dagre.ts**

```bash
git mv frontend/src/lib/layout.ts frontend/src/lib/layout-dagre.ts
```

- [ ] **Step 2: Create new dispatcher at layout.ts**

Create `frontend/src/lib/layout.ts`:

```typescript
import { layoutModelGraph as layoutDagre } from "./layout-dagre";
import { layoutModelGraph as layoutElk } from "./layout-elk";

export type { LayoutOptions } from "./layout-dagre";

const ENGINE: "dagre" | "elk" = "dagre";

export const layoutModelGraph = ENGINE === "elk" ? layoutElk : layoutDagre;
```

- [ ] **Step 3: Run typecheck and tests**

```bash
cd frontend && pnpm run build && pnpm test
```

Expected: TypeScript clean (App.tsx import of `./lib/layout` still resolves), all tests pass.

- [ ] **Step 4: Commit**

```bash
git add frontend/src/lib/layout.ts frontend/src/lib/layout-dagre.ts
git commit -m "$(cat <<'EOF'
refactor(frontend): introduce engine dispatcher for layout

layout.ts becomes a thin dispatcher selecting between layout-dagre and
layout-elk via an internal ENGINE constant. Default stays on dagre to
keep current behavior; flipping to "elk" is a one-line change for
visual A/B in the next task.
EOF
)"
```

---

## Task 7: Switch dispatcher to ELK and verify in sandbox

**Files:**
- Modify: `frontend/src/lib/layout.ts` (one line)

- [ ] **Step 1: Flip the engine flag**

Change in `frontend/src/lib/layout.ts`:

```typescript
const ENGINE: "dagre" | "elk" = "elk";
```

- [ ] **Step 2: Run tests**

```bash
cd frontend && pnpm run build && pnpm test
```

Expected: all tests pass. layout-elk.test.ts is exercising elk directly; App.tsx importing layout.ts now gets the elk path.

- [ ] **Step 3: Manual sandbox A/B comparison**

```bash
cd plugin && ./gradlew runIde -Pide.project=/Users/kouko/DataspellProjects/iCHEF-dbt-pipeline
```

Pick a hub model from iCHEF (the manifest analysis identified models with up to 38 fan-in). Compare the visual at hop=1, hop=2 against your memory / a screenshot from before this commit.

- Spacing should look comparable.
- The hub layer will still be a tall vertical wall at this point — `layerUnzipping` is not yet enabled.

If the layout looks structurally broken (e.g. nodes overlapping, x-order wrong), capture a screenshot and bisect the ELK options in `layout-elk.ts`.

- [ ] **Step 4: Commit**

```bash
git add frontend/src/lib/layout.ts
git commit -m "$(cat <<'EOF'
feat(frontend): switch layout dispatcher to elk engine

ELK matches dagre's general layout shape on the iCHEF dbt project.
Hub layers still stack vertically — layerUnzipping is added in the
next task.
EOF
)"
```

---

## Task 8: Enable layerUnzipping for hub layers

**Files:**
- Modify: `frontend/src/lib/layout-elk.ts`

- [ ] **Step 1: Add layerUnzipping options**

In `frontend/src/lib/layout-elk.ts`, expand the `layoutOptions` block on the root graph:

```typescript
    layoutOptions: {
      "elk.algorithm": "layered",
      "elk.direction": direction,
      "elk.layered.spacing.nodeNodeBetweenLayers": String(opts.ranksepY),
      "elk.spacing.nodeNode": String(opts.nodesepX),
      "elk.layered.layerUnzipping.strategy": "ALTERNATING",
      "elk.layered.layerUnzipping.layerSplit": "2",
      "elk.layered.layerUnzipping.resetOnLongEdges": "true",
    },
```

- [ ] **Step 2: Run tests**

```bash
cd frontend && pnpm test layout-elk
```

Expected: 3 passing + 1 todo (no regression on the empty / linear / variable-height cases).

- [ ] **Step 3: Manual sandbox check on hub model**

```bash
cd plugin && ./gradlew runIde -Pide.project=/Users/kouko/DataspellProjects/iCHEF-dbt-pipeline
```

Open the hub model identified earlier. The previously tall vertical layer should now be split into two staggered sub-columns. Total layer height should be roughly halved.

If the unzipping does not visually engage:
- Verify the option strings are exact (case-sensitive).
- Confirm the layer in question has enough nodes (`layerSplit=2` is the threshold; layers with ≤2 nodes won't split).

- [ ] **Step 4: Commit**

```bash
git add frontend/src/lib/layout-elk.ts
git commit -m "$(cat <<'EOF'
feat(frontend): enable layerUnzipping ALTERNATING in layout-elk

Splits tall fan-in/out layers into 2 staggered sub-columns, halving the
visual height of hub-layer walls. resetOnLongEdges keeps long pass-through
edges from disrupting the alternation.
EOF
)"
```

---

## Task 9: Tune node placement and crossing minimization

**Files:**
- Modify: `frontend/src/lib/layout-elk.ts`

- [ ] **Step 1: Add Brandes-Köpf node placement and thoroughness**

In `frontend/src/lib/layout-elk.ts`, add to `layoutOptions`:

```typescript
      "elk.layered.nodePlacement.strategy": "BRANDES_KOEPF",
      "elk.layered.thoroughness": "10",
```

- [ ] **Step 2: Run tests**

```bash
cd frontend && pnpm test
```

Expected: all tests pass.

- [ ] **Step 3: Manual sandbox visual A/B**

```bash
cd plugin && ./gradlew runIde -Pide.project=/Users/kouko/DataspellProjects/iCHEF-dbt-pipeline
```

Compare hop=1, hop=2, hop=3 on:
- A linear-shaped model (low fan)
- The hub model (high fan)
- A medium model (3-5 fan)

Edges should be straighter overall. Hub layer should remain visually clean. If anything looks worse, drop `thoroughness` back to default (`7`) or revert just that line.

- [ ] **Step 4: Commit**

```bash
git add frontend/src/lib/layout-elk.ts
git commit -m "$(cat <<'EOF'
feat(frontend): use Brandes-Köpf node placement, thoroughness 10

BRANDES_KOEPF produces straighter edges than the default LINEAR_SEGMENTS
placement on graphs with hub topology. thoroughness=10 spends more time
on crossing minimization; the hop-bounded scope makes this acceptable.
EOF
)"
```

---

## Task 10: Loading transition — preserve last positions on payload change

**Files:**
- Modify: [frontend/src/App.tsx](../../../frontend/src/App.tsx)

The Task 5 refactor sets fresh nodes to `{x:0, y:0}` for one frame while ELK computes. On hop change this causes a brief flash. Fix by leaving stale positions in place until the new layout resolves.

- [ ] **Step 1: Modify the merging useMemo**

In the `derivedNodes` useMemo (the one defined in Task 5), keep the same behavior as before but no special change needed — the fix is in how `positions` is updated. Find the useEffect added in Task 5 and modify so it does *not* clear `positions` before the new layout resolves:

The current code already does this correctly (it calls `setPositions(next)` *after* layout resolves, never clears beforehand). But during a hop change, `rawNodes` changes id-set: some new ids have no position yet → fall to `{x:0, y:0}`.

Replace the merging useMemo with this stale-aware version:

```typescript
const derivedNodes: Node[] = useMemo(() => {
  return rawNodes.map((n) => {
    const manual = manualPositions[n.id];
    const auto = positions[n.id];
    // If a node has no auto position yet, hide it visually rather than
    // letting it flash at the origin. ReactFlow will start rendering it
    // once the layout effect resolves and a real position arrives.
    if (!manual && !auto) {
      return { ...n, hidden: true, width: NODE_WIDTH, height: heights[n.id] ?? 60 };
    }
    return {
      ...n,
      position: manual ?? auto!,
      width: NODE_WIDTH,
      height: heights[n.id] ?? 60,
    };
  });
}, [rawNodes, positions, manualPositions, heights]);
```

(The change: introduce `hidden: true` when no position is known. ReactFlow honors `hidden` and skips rendering — see https://reactflow.dev/api-reference/types/node#hidden.)

- [ ] **Step 2: Run typecheck and tests**

```bash
cd frontend && pnpm run build && pnpm test
```

Expected: TypeScript clean, all tests pass.

- [ ] **Step 3: Manual sandbox check**

```bash
cd plugin && ./gradlew runIde -Pide.project=/Users/kouko/DataspellProjects/iCHEF-dbt-pipeline
```

Change hop several times in a row. Nodes should fade in to their final positions rather than flashing at the origin. Existing nodes that survive the hop change should stay visible the whole time.

- [ ] **Step 4: Commit**

```bash
git add frontend/src/App.tsx
git commit -m "$(cat <<'EOF'
fix(frontend): hide nodes without resolved positions to prevent origin flash

When the model set changes (e.g. hop adjustment), brand-new nodes have no
auto position until the next ELK resolve. Marking them hidden until a
position arrives avoids a one-frame flash at (0, 0).
EOF
)"
```

---

## Task 11: Promote hub-case test from todo to real assertion

**Files:**
- Modify: `frontend/src/lib/layout-elk.test.ts`

- [ ] **Step 1: Replace the it.todo with a real test**

Replace the `it.todo(...)` block in `frontend/src/lib/layout-elk.test.ts` with:

```typescript
describe("layoutModelGraph (elk) — hub case", () => {
  it("uses layerUnzipping to spread 30 fan-in siblings across multiple sub-columns", async () => {
    const parents = Array.from({ length: 30 }, (_, i) => makeNode(`p${i}`));
    const sink = makeNode("sink");
    const nodes = [...parents, sink];
    const edges = parents.map((p, i) => makeEdge(`e${i}`, p.id, "sink"));
    const heights = Object.fromEntries(nodes.map((n) => [n.id, 60]));

    const result = await layoutModelGraph(nodes, edges, {
      rankdir: "LR",
      nodeWidth: 200,
      nodesepX: 60,
      ranksepY: 100,
      heights,
    });

    // The 30 parents should occupy at least 2 distinct x-bands (sub-columns).
    const parentXs = result
      .filter((n) => n.id.startsWith("p"))
      .map((n) => Math.round(n.position.x));
    const distinctX = new Set(parentXs);
    expect(distinctX.size).toBeGreaterThanOrEqual(2);
  });
});
```

- [ ] **Step 2: Run tests**

```bash
cd frontend && pnpm test layout-elk
```

Expected: 4 passing tests.

If the test fails (only one distinct x), inspect: is `layerSplit` correctly set to `"2"` (string, not number — ELK expects strings)? Is `layerUnzipping.strategy` exact case `ALTERNATING`?

- [ ] **Step 3: Commit**

```bash
git add frontend/src/lib/layout-elk.test.ts
git commit -m "$(cat <<'EOF'
test(frontend): assert layerUnzipping splits 30 fan-in into ≥2 sub-columns

Promotes the hub-case it.todo to a real regression guard now that
ALTERNATING strategy is wired in.
EOF
)"
```

---

## Task 12: Full test sweep + visual regression sanity

**Files:** none changed.

- [ ] **Step 1: Run full frontend test suite**

```bash
cd frontend && pnpm test
```

Expected: all suites pass (DbtModelNode, HopStepper, lineage-trace, theme, layout-elk).

- [ ] **Step 2: Run Kotlin test suite**

```bash
cd plugin && ./gradlew test
```

Expected: ~90 tests pass. (Layout change is frontend-only; Kotlin should be unaffected.)

- [ ] **Step 3: Manual visual regression in sandbox**

```bash
cd plugin && ./gradlew runIde -Pide.project=/Users/kouko/DataspellProjects/iCHEF-dbt-pipeline
```

Walk through this checklist:
- [ ] Open a non-hub model, hop=1 — looks reasonable
- [ ] Open the hub model, hop=1 — sub-column wrapping visible
- [ ] Hop=2 from hub — depth+breadth still readable
- [ ] Expand a column-heavy model (>50 cols) — card height handled, no overlap with neighbors
- [ ] Click a column — lineage trace highlights correctly
- [ ] Drag a node — manual position persists; auto-layout does not snap it back
- [ ] Open a different model file — view re-layouts cleanly

Any failure here = a real issue. Capture screenshots and decide whether to fix in this branch or roll back.

- [ ] **Step 4: No commit (verification-only task)**

This task produces no code changes; it gates whether to proceed to cutover.

---

## Task 13: Cutover — remove dagre

**Files:**
- Delete: `frontend/src/lib/layout-dagre.ts`
- Modify: `frontend/src/lib/layout.ts`
- Modify: `frontend/package.json`

- [ ] **Step 1: Inline layout-elk into layout.ts**

Replace `frontend/src/lib/layout.ts` with the contents of `frontend/src/lib/layout-elk.ts` (i.e. become the elk implementation directly, no dispatcher):

```typescript
import ELK from "elkjs/lib/elk.bundled.js";
import type { Edge, Node } from "@xyflow/react";

export interface LayoutOptions {
  rankdir?: "LR" | "TB";
  nodeWidth: number;
  nodesepX: number;
  ranksepY: number;
  heights: Record<string, number>;
}

const elk = new ELK();

export async function layoutModelGraph(
  nodes: Node[],
  edges: Edge[],
  opts: LayoutOptions,
): Promise<Node[]> {
  if (nodes.length === 0) return [];

  const direction = (opts.rankdir ?? "LR") === "LR" ? "RIGHT" : "DOWN";
  const graph = {
    id: "root",
    layoutOptions: {
      "elk.algorithm": "layered",
      "elk.direction": direction,
      "elk.layered.spacing.nodeNodeBetweenLayers": String(opts.ranksepY),
      "elk.spacing.nodeNode": String(opts.nodesepX),
      "elk.layered.layerUnzipping.strategy": "ALTERNATING",
      "elk.layered.layerUnzipping.layerSplit": "2",
      "elk.layered.layerUnzipping.resetOnLongEdges": "true",
      "elk.layered.nodePlacement.strategy": "BRANDES_KOEPF",
      "elk.layered.thoroughness": "10",
    },
    children: nodes.map((n) => ({
      id: n.id,
      width: opts.nodeWidth,
      height: opts.heights[n.id] ?? 60,
    })),
    edges: edges.map((e) => ({
      id: e.id,
      sources: [e.source],
      targets: [e.target],
    })),
  };

  const result = await elk.layout(graph);
  const childById = new Map((result.children ?? []).map((c) => [c.id, c]));

  return nodes.map((n) => {
    const c = childById.get(n.id);
    const height = opts.heights[n.id] ?? 60;
    return {
      ...n,
      position: { x: c?.x ?? 0, y: c?.y ?? 0 },
      width: opts.nodeWidth,
      height,
    };
  });
}
```

- [ ] **Step 2: Delete layout-dagre.ts and layout-elk.ts**

```bash
git rm frontend/src/lib/layout-dagre.ts frontend/src/lib/layout-elk.ts
```

- [ ] **Step 3: Move test file**

```bash
git mv frontend/src/lib/layout-elk.test.ts frontend/src/lib/layout.test.ts
```

In the new `frontend/src/lib/layout.test.ts`, change the import from `./layout-elk` to `./layout`:

```typescript
import { layoutModelGraph } from "./layout";
```

- [ ] **Step 4: Remove dagre from package.json**

```bash
cd frontend && pnpm remove dagre @types/dagre
```

- [ ] **Step 5: Run full test sweep**

```bash
cd frontend && pnpm run build && pnpm test
```

Expected: TypeScript clean, all tests pass.

- [ ] **Step 6: Final manual sandbox check**

```bash
cd plugin && ./gradlew runIde -Pide.project=/Users/kouko/DataspellProjects/iCHEF-dbt-pipeline
```

Walk the same checklist as Task 12 step 3. Everything still works. Bundle is now smaller by `dagre`'s footprint and bigger by `elkjs`'s — net change is roughly +1.3MB.

- [ ] **Step 7: Commit**

```bash
git add frontend/src/lib/layout.ts frontend/src/lib/layout.test.ts frontend/package.json frontend/pnpm-lock.yaml
git commit -m "$(cat <<'EOF'
refactor(frontend): cut over to elkjs, drop dagre

Inline the elk implementation into layout.ts, delete layout-dagre.ts
and layout-elk.ts, rename layout-elk.test.ts to layout.test.ts.
Remove dagre and @types/dagre from package.json.

Bundle size delta: roughly +1.3MB net (elkjs ~1.5MB - dagre ~200KB).
EOF
)"
```

---

## Manual Verification Checklist (final)

After Task 13, run this once to confirm the migration is healthy:

- [ ] `cd frontend && pnpm test` — all green
- [ ] `cd frontend && pnpm run build` — succeeds, no TS errors
- [ ] `cd plugin && ./gradlew test` — all green
- [ ] `cd plugin && ./gradlew runIde -Pide.project=/Users/kouko/DataspellProjects/iCHEF-dbt-pipeline` — open dbtree:
  - Hub model at hop=1 shows sub-column wrapping
  - Non-hub model at hop=1 looks normal
  - Hop change does not flash nodes at origin
  - Column expand / collapse works
  - Drag persists position
  - Lineage trace highlight works

---

## Rollback

Each task commits cleanly. To roll back:

- After any single task: `git revert <sha>` of that task's commit.
- After Task 13 cutover but before push: `git reset --hard <pre-cutover-sha>` on this branch.
- After merge to main: `git revert <merge-sha>` followed by `pnpm install` to restore dagre.
