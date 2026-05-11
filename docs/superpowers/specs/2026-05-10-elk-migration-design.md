# ELK Migration Design — Layout engine swap (dagre → elkjs)

**Date**: 2026-05-10
**Status**: Design — implementation not started
**Scope**: `frontend/` only

## 1. Problem statement

The current model-DAG layout uses dagre via [layout.ts](../../../frontend/src/lib/layout.ts).
Dagre handles common cases well but cannot solve a specific recurring pain point in
real dbt projects: **a single model with high fan-in or fan-out causes that layer to
become a tall vertical "wall" that does not fit the screen and is hard to read**.

Concrete numbers from a representative real project (iCHEF dbt-pipeline, 824 models):

- max fan-in = 38, max fan-out = 28
- median fan-in/out = 1, p90 = 3 — *most* models are fine
- 16 tables have >100 columns, max 275

So the issue concentrates on a small number of hub models. At hop=1 from such a hub,
dagre stacks 30+ siblings vertically in one layer.

## 2. Goal

Replace dagre with elkjs (Eclipse Layout Kernel JS port), and enable
`layerUnzipping` to break tall hub layers into multiple sub-columns so the
total height is reduced.

Secondary benefit: ELK exposes port constraints, edge priorities, partitioning,
interactive mode and other knobs that dbtree may want later (column-level edge
alignment, focal-model centering, folder grouping). Migration is the prerequisite
for those follow-up features but they are out of scope for this design.

## 3. Non-goals

- No data-model change (models stay atomic, columns stay rendered as rows inside
  the card; no compound-node refactor).
- No new UX (no folder grouping, no semantic zoom, no top-K + show-more) — these
  are orthogonal to the algorithm swap and tracked separately.
- No performance work for hop=∞ on 1000+ model graphs — current scope is hop-bounded
  windows.
- No JetBrains settings UI for engine choice — engine selection is internal.

## 4. Current architecture

```
App.tsx
  └─ derivedNodes: Node[] = useMemo(() => {
       heights = compute()                     // sync
       rawNodes = payload.models.map(...)      // sync
       positioned = layoutModelGraph(...)      // sync (dagre)
       return positioned.map(merge with manualPositions)
     }, [...12 deps])

layout.ts
  └─ layoutModelGraph(nodes, edges, opts): Node[]   // sync wrapper around dagre.layout()
```

Key facts:

- `layoutModelGraph` is the *only* entry point — boundary is clean.
- It is called inside `useMemo`. **`useMemo` cannot return a Promise**, so
  switching to elkjs (async) forces a state-shape refactor in App.tsx.
- 63 LOC in [layout.ts](../../../frontend/src/lib/layout.ts), no existing test for
  this file.

## 5. Target architecture

```
App.tsx
  ├─ rawNodes:   useMemo(() => {...})               // sync, pure data
  ├─ positions:  useState<Record<id, {x,y}>>
  │              + useEffect(() => layout(rawNodes).then(setPositions), [...])
  └─ derivedNodes: useMemo(() =>
       merge(rawNodes, positions, manualPositions))

layout.ts (renamed or kept; one entry point)
  └─ layoutModelGraph(nodes, edges, opts): Promise<Node[]>
       └─ build ELK graph JSON → elk.layout() → map back to React Flow nodes
```

Key changes:

- `layoutModelGraph` becomes async (`Promise<Node[]>`).
- `derivedNodes` useMemo splits into three: pure data (sync), positions (async),
  merged final (sync).
- Loading state: while positions are being computed for a fresh `payload`, render
  rawNodes at last known positions or at origin (decision deferred to Phase 3).

## 6. Implementation order

8 phases. Each phase is independently committable and revertable.

### Phase 0 — JCEF compatibility spike

**Goal**: verify elkjs runs at all in the JCEF environment before investing.

- Add `elkjs` to `frontend/package.json`.
- In a throwaway commit, render a 5-node toy graph through `elk.layout()`.
- Run `./gradlew runIde` and check the JCEF console for worker / blob: URL errors.

**Exit**: 5-node toy graph produces coordinates, JCEF console clean.

**Failure mode**: JCEF blocks Web Worker or blob: URLs → switch elkjs to inline /
sync mode (`new ELK({ workerFactory: undefined })`), accept slight UI block on
layout call.

### Phase 1 — Parallel implementation `layout-elk.ts`

**Goal**: new and old coexist, dagre untouched.

- Create `frontend/src/lib/layout-elk.ts`.
- Mirror the existing `LayoutOptions` interface from layout.ts; same field names.
- Signature: `layoutModelGraph(...): Promise<Node[]>` (async).
- Internals: build ELK graph JSON from React Flow nodes/edges, call
  `elk.layout()`, map ELK output back to `Node[]` (top-left x/y, plus width/height
  for MiniMap).
- Starter ELK options:
  - `'elk.algorithm': 'layered'`
  - `'elk.direction': 'RIGHT'`
  - `'elk.layered.spacing.nodeNodeBetweenLayers': '100'`
  - `'elk.spacing.nodeNode': '60'`

**Exit**: feeding the same input as dagre produces a layout of comparable shape
(not pixel-identical; same general structure).

### Phase 2 — Unit tests for layout-elk.ts

**Goal**: align with the project's "stop at logic-layer tests" Tier-1 standard.

- New file `frontend/src/lib/layout-elk.test.ts` (vitest).
- Cases:
  1. Empty graph returns `[]`.
  2. Linear 5-node chain — verify monotonic x progression in LR mode.
  3. Variable-height nodes — verify y coordinates do not overlap.
  4. Hub case: 1 node with 30 incoming edges — written as `it.todo(...)` in
     Phase 2; promoted to a real assertion in Phase 5 once `layerUnzipping`
     is enabled (asserts the parent layer occupies multiple sub-columns).

**Exit**: `pnpm test` passes.

### Phase 3 — Refactor App.tsx state shape

**Goal**: split sync data from async positions.

- Touch [App.tsx:392-456](../../../frontend/src/App.tsx#L392-L456):
  - Extract `rawNodes` to its own useMemo (data only, no layout call).
  - Add `positions` useState + useEffect that calls `layoutModelGraph` and
    `setPositions` on resolve.
  - Rebuild `derivedNodes` useMemo as a merge of rawNodes + positions +
    manualPositions.
- Loading transition handling:
  - On `payload` change while positions are stale, prefer to keep last positions
    until new ones arrive (no flash to origin).
  - On Phase 3 completion still calling dagre (sync) — useEffect resolves
    immediately so behavior identical to before.

**Exit**: hop change / column expand / drag interactions behave as before.
Existing frontend tests still pass.

### Phase 4 — Engine flag dispatcher

**Goal**: be able to A/B compare dagre vs ELK without code surgery.

- Single internal const in a layout dispatcher:
  ```ts
  const ENGINE: "dagre" | "elk" = "elk";
  ```
- Dispatcher imports both implementations and selects.
- No JetBrains settings UI yet — keep change frontend-only.

**Exit**: `runIde` against the iCHEF project, flipping the const + reload shows
both algorithms' output for the same hop scope.

### Phase 5 — Enable layerUnzipping and tune

**Goal**: deliver the actual hub-problem fix.

- Find the highest-fan-in/out model in iCHEF manifest (likely fan-in=38).
- Enable:
  - `'elk.layered.layerUnzipping.strategy': 'ALTERNATING'`
  - `'elk.layered.layerUnzipping.layerSplit': '2'`
  - `'elk.layered.layerUnzipping.resetOnLongEdges': 'true'` (default; explicit for clarity)
- Tune additionally:
  - `'elk.layered.nodePlacement.strategy': 'BRANDES_KOEPF'` (straighter edges)
  - `'elk.layered.thoroughness': '10'` (better crossing minimization)
- Visual A/B: dagre vs ELK base vs ELK + unzipping on the hub case.

**Exit**: hub case visibly improved. Common (low-fan) cases not regressed.

### Phase 6 — Test suites and visual regression

- `cd frontend && pnpm test` (unit).
- `./gradlew test` (Kotlin side, sanity).
- `./gradlew runIde -Pide.project=/path/to/iCHEF` — manually validate hop=1/2/3
  on the hub model and on a regular model.

**Exit**: maintainer (kouko) approves visual quality.

### Phase 7 — Cutover

- Delete `frontend/src/lib/layout.ts` (dagre version).
- Rename `layout-elk.ts` → `layout.ts` (so import sites stay short).
- Remove `dagre` and `@types/dagre` from `frontend/package.json`.
- Drop the engine flag (no longer needed) — or keep as escape hatch (decide at
  cutover time).
- README does not currently surface dagre as an implementation detail; no doc
  update needed.

**Exit**: CI green.

### Phase 8 — (Out of scope; future) ELK-only features

Not part of this migration. Tracked separately:

- Focal model centering via `elk.priority`.
- Hop-switch position preservation via `'elk.interactive': true`.
- Folder grouping via compound nodes + `hierarchyHandling`.
- Column-level port alignment via `portConstraints: FIXED_POS`.

## 7. Risks and mitigations

| Risk | Surfaces in | Mitigation |
|---|---|---|
| JCEF blocks Web Worker / blob: URLs | Phase 0 | Switch to elkjs inline / sync mode (slight UI block, acceptable for hop-bounded scopes) |
| ELK rejects dbt unique_id with dotted form (`model.proj.name`) | Phase 1 first call | Escape IDs going into ELK (e.g. replace `.` with `__`), unescape on extraction |
| ELK default config looks worse than dagre | Phase 5 | Expected — phase 5 IS the tuning work; budget 1-2 days |
| useEffect re-render flash on hop change | Phase 3 | Keep stale positions until new ones resolve; do not fall back to origin |
| Bundle size +1.3MB grows plugin zip | Phase 7 | Measure before cutover; if unacceptable, evaluate dynamic import (load layout engine on first layout call) |

## 8. Time estimate

| With buffer | Without buffer |
|---|---|
| 5-8 working days | 3-5 working days |

Phase 0 + 1 + 2 (~2 days) is the riskiest section because of JCEF unknowns and
async refactor in Phase 3. Phases 4-6 are iterative tuning and depend on visual
judgment — could be 0.5-2 days each depending on how picky the maintainer is.

## 9. Verification checkpoints

- After Phase 0: a screenshot from JCEF console showing successful 5-node layout.
- After Phase 5: side-by-side screenshot of dagre vs ELK on the hub case (38-fan-in
  model in iCHEF).
- After Phase 6: green `pnpm test` + `./gradlew test` + manual sandbox session.

## 10. Rollback

Each phase has a clean revert via `git revert`:

- Phases 0-6: dagre still in `package.json`, layout.ts untouched. Reverting one
  phase commit returns to fully working dagre state.
- Phase 7 cutover: `git revert <cutover-sha>` restores layout.ts and re-adds dagre.
- After Phase 7 lands on `main`, the rollback path is `git revert` followed by
  `pnpm install` to restore dagre.

## 11. Open questions

- Should we keep the engine flag in production (Phase 7) as an escape hatch, or
  delete it for cleanliness? Defer to cutover time.
- If JCEF blocks workers in Phase 0, is sync mode performance acceptable for
  hop=∞ on 824 models? Measure and decide; not blocking unless hop=∞ becomes
  default usage.
