import { useCallback, useEffect, useMemo, useState } from "react";
import {
  Background,
  Controls,
  MiniMap,
  ReactFlow,
  type Edge,
  type EdgeTypes,
  type Node,
  type NodeChange,
  type NodeTypes,
} from "@xyflow/react";
import "@xyflow/react/dist/style.css";

import demoFixture from "./fixtures/lineage-demo.json";
import type { ColumnSpec, LineagePayload } from "./types";
import { CurvedBezierEdge } from "./components/CurvedBezierEdge";
import { DbtModelNode, type DbtModelNodeData } from "./components/DbtModelNode";
import { KeyboardShortcuts } from "./components/KeyboardShortcuts";
import {
  HIDDEN_GHOST_HEIGHT,
  HiddenNeighboursGhost,
  type HiddenNeighboursGhostData,
} from "./components/HiddenNeighboursGhost";
import { HopStepper, isUnlimited } from "./components/HopStepper";
import { layoutModelGraph } from "./lib/layout";
import {
  buildColumnLineageTrace,
  buildColumnTraceEdgePairs,
  buildModelTrace,
  edgeKey,
  isEdgeOnModelTreePath,
} from "./lib/lineage-trace";
import {
  applyColumnEdgesDelta,
  mergePayloadPreservingColumns,
  type ColumnEdgesDelta,
} from "./lib/payload-reducer";
import { THEMES, detectInitialTheme, normalizeLayer, type Theme, type ThemeName } from "./lib/theme";

const NODE_TYPES: NodeTypes = {
  dbtModel: DbtModelNode,
  dbtGhost: HiddenNeighboursGhost,
};
const ghostId = (uid: string, side: "upstream" | "downstream") =>
  `__hidden__${uid}__${side}`;
const EDGE_TYPES: EdgeTypes = { curved: CurvedBezierEdge };
const NODE_WIDTH = 320;
// Max time a uid may remain in pendingColumns before being force-cleared
// by the safety-net effect. Pegged comfortably above the Kotlin sidecar
// default timeout (60s) so a healthy slow response isn't snipped — this
// only fires when the publish back was actually lost.
const PENDING_COLUMNS_TIMEOUT_MS = 90_000;
// Empirical char-per-line estimate at NODE_WIDTH=320, font-weight 600 / 12px,
// after subtracting padding + layer chip + chevron button width. Used only
// to feed the layout engine an approximate per-node height; actual rendering
// is done by the browser's wordBreak.
const CHARS_PER_NAME_LINE = 19;
const NAME_LINE_HEIGHT = 16;
const HEADER_BASE_HEIGHT = 32; // padding + first name line
// Column-row constants. Both name and type can wrap; the row's visible height
// is the taller of the two columns. Char budgets are calibrated for the
// 50/50 flex split (name flex:1 1 auto, type max-width:50%) at NODE_WIDTH=320,
// font sizes 10px (name) / 9px (type).
const COLUMN_NAME_CHARS_PER_LINE = 18; // monospace 10px in ~140px column
const COLUMN_TYPE_CHARS_PER_LINE = 18; // monospace 9px in ~140px column
const COLUMN_LINE_HEIGHT = 13; // matches CSS lineHeight 1.3 × 10px
const COLUMN_ROW_PADDING = 6; // CSS "3px 12px" → 3+3 vertical padding
const COLS_VERTICAL_PADDING = 12;

/** Cap at this many column rows before scrolling. */
export const COLUMN_SCROLL_THRESHOLD = 15;

/**
 * Visible scroll-viewport height when capping kicks in. Derived from threshold
 * so the heights memo and DbtModelNode's maxHeight stay in sync automatically.
 */
export const COLUMN_LIST_MAX_HEIGHT =
  COLUMN_SCROLL_THRESHOLD * (COLUMN_LINE_HEIGHT + COLUMN_ROW_PADDING);

/**
 * Stable empty Set for nodes off the current column-lineage trace.
 * `lineageTrace.columns.get(uid) ?? new Set()` would mint a fresh Set
 * for every off-trace node on every render — and the resulting prop
 * identity change punches through xyflow's shallow node-data memo,
 * triggering needless DbtModelNode re-renders. One module-level value
 * keeps the reference stable across renders.
 */
const EMPTY_HIGHLIGHTED_COLUMNS: Set<string> = new Set();

const PLUGIN_HOST = "intellij-dbtree.local";
const isInsidePlugin =
  typeof window !== "undefined" && window.location.hostname === PLUGIN_HOST;

interface HostState {
  up_hops: number;
  down_hops: number;
}

declare global {
  interface Window {
    setLineageInfo?: (payload: LineagePayload) => void;
    setSelectedModel?: (uniqueId: string) => void;
    setIdeTheme?: (theme: ThemeName) => void;
    applyHostState?: (state: HostState) => void;
    /**
     * Surgical patch: update only the [columns] of one model in the payload.
     * Used after the Kotlin side resolves a column list lazily via the
     * sqlglot sidecar — avoids re-pushing the whole DAG.
     */
    applyModelColumns?: (uniqueId: string, columns: ColumnSpec[]) => void;
    /**
     * Streaming column-edges patch: append new edges, optionally flip the
     * column_lineage_done flag, optionally surface a warning. Used by
     * the Kotlin sidecar's stream-publish path so the JCEF bridge no
     * longer JSON-parses a full payload every 500ms while a column
     * trace is streaming — the cause of the dbtree-panel freeze on
     * iCHEF-sized projects.
     */
    applyColumnEdgesDelta?: (delta: ColumnEdgesDelta) => void;
    kotlinCallback?: (payload: string) => void;
    __DBTREE_THEME__?: ThemeName;
    __DBTREE_HOST_STATE__?: HostState;
  }
  // Injected by Vite at build time; see vite.config.ts.
  const __DBTREE_BUILD_ID__: string;
}

const EMPTY_PAYLOAD: LineagePayload = {
  models: [],
  model_edges: [],
  column_edges: [],
};

const DEFAULT_HOPS = 3;

function App() {
  // ---- Theme ---------------------------------------------------------------
  const [themeName, setThemeName] = useState<ThemeName>(() => detectInitialTheme());
  const theme = THEMES[themeName];

  useEffect(() => {
    window.setIdeTheme = (next) => setThemeName(next);
    return () => {
      delete window.setIdeTheme;
    };
  }, []);

  // ---- Hops ----------------------------------------------------------------
  const [upHops, setUpHops] = useState<number>(
    () => window.__DBTREE_HOST_STATE__?.up_hops ?? DEFAULT_HOPS,
  );
  const [downHops, setDownHops] = useState<number>(
    () => window.__DBTREE_HOST_STATE__?.down_hops ?? DEFAULT_HOPS,
  );

  useEffect(() => {
    window.applyHostState = (s) => {
      setUpHops(s.up_hops);
      setDownHops(s.down_hops);
    };
    return () => {
      delete window.applyHostState;
    };
  }, []);

  const sendHopChange = useCallback((up: number, down: number) => {
    if (!window.kotlinCallback) return;
    window.kotlinCallback(
      JSON.stringify({ event: "HOP_CHANGE", up_hops: up, down_hops: down }),
    );
  }, []);

  const onUpHops = useCallback(
    (next: number) => {
      setUpHops(next);
      sendHopChange(next, downHops);
    },
    [downHops, sendHopChange],
  );
  const onDownHops = useCallback(
    (next: number) => {
      setDownHops(next);
      sendHopChange(upHops, next);
    },
    [upHops, sendHopChange],
  );

  const onRefresh = useCallback(() => {
    if (!window.kotlinCallback) return;
    // Clear the "attempted" memo so models whose first prefetch failed
    // (e.g. Python interpreter wasn't configured yet, sidecar timed out,
    // sqlglot returned empty) get a fresh REQUEST_COLUMNS in the
    // prefetch wave that follows the refresh-driven setLineageInfo.
    // Backend mirrors this — refreshFromDisk invalidates columnListCache —
    // so the new requests actually re-run sqlglot rather than hitting a
    // stale empty cache entry.
    setAttemptedColumns((prev) => (prev.size === 0 ? prev : new Set()));
    window.kotlinCallback(JSON.stringify({ event: "REFRESH" }));
  }, []);

  // ---- Payload -------------------------------------------------------------
  const [payload, setPayload] = useState<LineagePayload>(() =>
    isInsidePlugin ? EMPTY_PAYLOAD : (demoFixture as LineagePayload),
  );

  /**
   * The model currently focused (orange border). Decoupled from the payload
   * so editor selection changes within the existing DAG can update the
   * highlight without re-rendering. Initialized from payload.selected when a
   * fresh payload arrives.
   */
  const [selectedModelUid, setSelectedModelUid] = useState<string | null>(
    () => payload.selected?.unique_id ?? null,
  );

  useEffect(() => {
    // When the focused model changes, drop the column selection if it
    // belongs to a different model. Doing this in the same handler that
    // sets selectedModelUid keeps both updates batched into one render —
    // a useEffect-based fallback would leave a transient frame where the
    // old column trace still drives edge highlighting.
    const dropStaleColumn = (newModel: string | null) => {
      setSelectedColumn((prev) =>
        prev && (newModel == null || prev.unique_id !== newModel) ? null : prev,
      );
    };
    window.setLineageInfo = (next) => {
      // Carry forward sidecar-patched columns from the prev payload so a
      // model navigation / hop change / dbt compile manifest reload
      // doesn't wipe them back to []. Without this, every fresh full
      // payload triggers a re-prefetch wave through the sidecar, and any
      // publish that silently drops in the Kotlin → JCEF → React chain
      // leaves the card stuck on "Parsing SQL…" with no recovery path.
      // See mergePayloadPreservingColumns docstring for the full rationale.
      setPayload((prev) => mergePayloadPreservingColumns(prev, next));
      const newModel = next.selected?.unique_id ?? null;
      setSelectedModelUid(newModel);
      dropStaleColumn(newModel);
    };
    window.setSelectedModel = (uid) => {
      setSelectedModelUid(uid);
      dropStaleColumn(uid);
    };
    window.applyModelColumns = (uid, columns) => {
      setPayload((prev) => ({
        ...prev,
        models: prev.models.map((m) =>
          m.unique_id === uid ? { ...m, columns } : m,
        ),
      }));
      setPendingColumns((prev) => {
        if (!prev.has(uid)) return prev;
        const next = new Set(prev);
        next.delete(uid);
        return next;
      });
      // Record that we got a terminal response (empty or full) so the
      // prefetch effect doesn't immediately re-fire for an empty
      // response. Without this, a model whose sidecar fails (no Python
      // interpreter, sqlglot can't parse, etc.) churns the IPC bus
      // indefinitely as setPayload-empty → setPending-clear → prefetch
      // re-fires → ... User can still retry by collapse + expand
      // (toggleExpanded calls requestColumnsIfNeeded with force: true,
      // which evicts from attemptedColumns).
      setAttemptedColumns((prev) => {
        if (prev.has(uid)) return prev;
        const next = new Set(prev);
        next.add(uid);
        return next;
      });
    };
    window.applyColumnEdgesDelta = (delta) => {
      setPayload((prev) => applyColumnEdgesDelta(prev, delta));
    };
    return () => {
      delete window.setLineageInfo;
      delete window.setSelectedModel;
      delete window.applyModelColumns;
      delete window.applyColumnEdgesDelta;
    };
  }, []);

  /**
   * Set of model uids whose columns are currently being computed by the
   * Kotlin side. Frontend uses this to (a) skip duplicate REQUEST_COLUMNS
   * events and (b) show a "Loading…" placeholder in the expanded card.
   */
  const [pendingColumns, setPendingColumns] = useState<Set<string>>(
    () => new Set(),
  );

  // uids that have already received a terminal applyModelColumns response
  // (success OR empty). Distinguishes "haven't asked yet" from "asked and
  // got back an empty list" — without this distinction the prefetch effect
  // re-fires for empty responses (since model.columns.length === 0) and
  // hammers the IPC bus. Eviction happens only on a manual force-retry
  // (toggleExpanded → requestColumnsIfNeeded({force: true})), so a user
  // can recover from a transient failure (e.g. Python interpreter just
  // got configured) by collapse + expand on the card.
  const [attemptedColumns, setAttemptedColumns] = useState<Set<string>>(
    () => new Set(),
  );

  // Safety net: auto-clear a uid from pendingColumns after this long,
  // even if no applyModelColumns response ever arrives. The
  // Kotlin → executeJavaScript → window.applyModelColumns chain has
  // multiple silent-failure paths (EDT contention, JCEF script drops on
  // transient page states, race with a concurrent setLineageInfo); without
  // this clamp the card stays on "Parsing SQL…" forever and the user has
  // no way to recover except restarting the IDE. After expiry, manually
  // re-expanding the card triggers a fresh REQUEST_COLUMNS via
  // toggleExpanded's force-retry path.
  useEffect(() => {
    if (pendingColumns.size === 0) return;
    const uids = Array.from(pendingColumns);
    const timers = uids.map((uid) =>
      setTimeout(() => {
        setPendingColumns((prev) => {
          if (!prev.has(uid)) return prev;
          const next = new Set(prev);
          next.delete(uid);
          return next;
        });
      }, PENDING_COLUMNS_TIMEOUT_MS),
    );
    return () => timers.forEach(clearTimeout);
  }, [pendingColumns]);

  // ---- Expanded models -----------------------------------------------------
  const [expanded, setExpanded] = useState<Set<string>>(() => {
    return new Set(payload.selected ? [payload.selected.unique_id] : []);
  });

  // Sticky preference for the toolbar's "expand all columns" toggle.
  // Without this, every fresh payload (clicking a different model,
  // hitting refresh, hop-slider rebuild) would reset the per-model
  // expanded state to empty because `allExpanded` is derived from
  // `expanded.has(uid)` for the new uids — none of which are in the
  // stale set. Tracking the user's intent separately means "expand
  // all" survives a redraw. Cleared automatically when the user
  // manually collapses any individual card (`toggleExpanded` below)
  // so the next redraw doesn't re-expand against their wishes.
  const [expandAllSticky, setExpandAllSticky] = useState(false);

  // Re-apply the sticky preference whenever the payload model set
  // changes. The effect only ADDS uids — never removes — so it can't
  // fight an in-progress individual expand. The `useEffect` early-
  // returns when the preference is off so the no-op path is cheap.
  useEffect(() => {
    if (!expandAllSticky) return;
    setExpanded((prev) => {
      let changed = false;
      const next = new Set(prev);
      for (const m of payload.models) {
        if (!next.has(m.unique_id)) {
          next.add(m.unique_id);
          changed = true;
        }
      }
      return changed ? next : prev;
    });
  }, [expandAllSticky, payload.models]);

  const [selectedColumn, setSelectedColumn] = useState<{
    unique_id: string;
    column: string;
  } | null>(() =>
    payload.selected?.column
      ? { unique_id: payload.selected.unique_id, column: payload.selected.column }
      : null,
  );

  // While the Python sidecar is computing column lineage, surface a
  // "computing…" hint in the toolbar so a 5-second wait doesn't look
  // like a hang. Cleared when the backend's response (new column_edges)
  // touches the column we're waiting on, or after 15s as a safety net
  // (matches the sidecar's own timeout).
  const [computingFor, setComputingFor] = useState<{
    unique_id: string;
    column: string;
  } | null>(null);

  // Clear the "computing…" hint when the streaming sidecar signals
  // it's done — payload.column_lineage_done flips to true on the
  // final flush (success or failure) and stays false during
  // intermediate batched publishes. With streaming we explicitly
  // CAN'T treat "first edge arrived" as done, because more edges
  // may follow.
  //
  // Safety net: 60s timeout matches the new default sidecar timeout
  // so the hint never gets stuck if the plugin somehow forgets to
  // emit a terminal payload.
  useEffect(() => {
    if (!computingFor) return;
    if (
      payload.column_lineage_done !== false &&
      payload.selected?.unique_id === computingFor.unique_id &&
      payload.selected?.column === computingFor.column
    ) {
      setComputingFor(null);
    }
  }, [computingFor, payload.column_lineage_done, payload.selected]);

  useEffect(() => {
    if (!computingFor) return;
    const t = setTimeout(() => setComputingFor(null), 60_000);
    return () => clearTimeout(t);
  }, [computingFor]);

  // When a new full payload arrives, drop column selection only when
  // the column is *truly* gone — i.e. the model is no longer in the
  // payload, OR the model has a populated column list that no longer
  // includes the selection.
  //
  // Empty column lists are NOT treated as "column gone" — they happen
  // mid-prefetch (basePayload reflects manifest yml/catalog only; the
  // sidecar's REQUEST_COLUMNS patches arrive separately via
  // applyModelColumns). Clearing on an empty list caused clicking a
  // sidecar-fetched column to flash the trace and then snap back as
  // the next setLineageInfo overwrote payload.models with empty
  // columns for that model.
  useEffect(() => {
    setSelectedColumn((prev) => {
      if (!prev) return prev;
      const model = payload.models.find((m) => m.unique_id === prev.unique_id);
      if (!model) return null;
      if (model.columns.length === 0) return prev;
      return model.columns.some((c) => c.name === prev.column) ? prev : null;
    });
  }, [payload]);

  // (Previously this auto-expanded the selected model on every selection
  // change. Removed — the user explicitly does not want clicking a card or
  // navigating to a model file to force the column list open. Expansion
  // is now driven purely by the chevron button or by clicking a column
  // row, both explicit user gestures. Stale column-selection cleanup is
  // now done synchronously inside window.setSelectedModel /
  // window.setLineageInfo so the same render that switches models also
  // clears the column trace.)

  /**
   * If the model has no columns yet (yml/catalog absent), fire a
   * REQUEST_COLUMNS event so Kotlin can compute the column list via
   * sqlglot. Idempotent: skipped when columns already populated or a
   * fetch is already in flight.
   */
  const requestColumnsIfNeeded = useCallback(
    (uniqueId: string, options: { force?: boolean } = {}) => {
      if (!isInsidePlugin || !window.kotlinCallback) return;
      const model = payload.models.find((m) => m.unique_id === uniqueId);
      if (!model || model.columns.length > 0) return;
      if (!options.force) {
        // Default: skip if a prior REQUEST_COLUMNS is still in flight, or
        // if we already received a terminal response (success or empty).
        if (pendingColumns.has(uniqueId)) return;
        if (attemptedColumns.has(uniqueId)) return;
      } else {
        // `force` overrides both gates so a manual expand can retry past
        // a dropped publish OR past a prior empty/failure response.
        setAttemptedColumns((prev) => {
          if (!prev.has(uniqueId)) return prev;
          const next = new Set(prev);
          next.delete(uniqueId);
          return next;
        });
      }
      setPendingColumns((prev) => {
        if (prev.has(uniqueId)) return prev;
        const next = new Set(prev);
        next.add(uniqueId);
        return next;
      });
      window.kotlinCallback(
        JSON.stringify({ event: "REQUEST_COLUMNS", unique_id: uniqueId }),
      );
    },
    [payload.models, pendingColumns, attemptedColumns],
  );

  // Prefetch column lists for every model in the current DAG as soon
  // as the payload arrives. Without this, columns only fetch when the
  // user expands a card, producing a visible ~1-second lag after
  // expand. Prefetching means the column list is usually already in
  // place by the time the user clicks. Fire-and-forget — the Kotlin
  // side handles each REQUEST_COLUMNS independently (column requests
  // don't bump the epoch, so concurrent prefetch requests don't
  // supersede each other or the active column-trace work).
  useEffect(() => {
    if (!isInsidePlugin || !window.kotlinCallback) return;
    const cb = window.kotlinCallback;
    const toRequest: string[] = [];
    for (const m of payload.models) {
      if (m.columns.length > 0) continue;
      if (pendingColumns.has(m.unique_id)) continue;
      // Skip uids that already received a terminal response (success
      // OR empty) — without this guard, an empty response (sidecar
      // failure / sqlglot parse miss) immediately triggers another
      // REQUEST_COLUMNS because applyModelColumns cleared pending and
      // the model's columns are still []. User can force a retry via
      // collapse + expand on the card.
      if (attemptedColumns.has(m.unique_id)) continue;
      toRequest.push(m.unique_id);
    }
    if (toRequest.length === 0) return;
    for (const uid of toRequest) {
      cb(JSON.stringify({ event: "REQUEST_COLUMNS", unique_id: uid }));
    }
    setPendingColumns((prev) => {
      const next = new Set(prev);
      for (const uid of toRequest) next.add(uid);
      return next;
    });
  }, [payload.models, pendingColumns, attemptedColumns]);

  const toggleExpanded = useCallback(
    (uniqueId: string) => {
      setExpanded((prev) => {
        const next = new Set(prev);
        if (next.has(uniqueId)) {
          next.delete(uniqueId);
          // Manual collapse opts out of "expand all" mode — otherwise
          // the next payload refresh would re-expand this card and
          // confuse the user.
          setExpandAllSticky(false);
        } else {
          next.add(uniqueId);
          // Going from collapsed → expanded: trigger lazy column fetch.
          // Force-retry past any stale pending flag so the user has a
          // working recovery path when a prior REQUEST_COLUMNS response
          // was silently dropped in the Kotlin → JCEF → React chain.
          // No-op if columns already populated.
          requestColumnsIfNeeded(uniqueId, { force: true });
        }
        return next;
      });
    },
    [requestColumnsIfNeeded],
  );

  const onColumnClick = useCallback((uniqueId: string, column: string) => {
    setSelectedColumn((prev) =>
      prev?.unique_id === uniqueId && prev?.column === column
        ? null
        : { unique_id: uniqueId, column },
    );
    setExpanded((prev) => {
      if (prev.has(uniqueId)) return prev;
      const next = new Set(prev);
      next.add(uniqueId);
      return next;
    });
    setSelectedModelUid(uniqueId);
    if (window.kotlinCallback) {
      // Open the model file in the IDE.
      window.kotlinCallback(JSON.stringify({ event: "NODE_CLICK", unique_id: uniqueId }));
      // Ask the backend (Phase C python-sidecar) to trace this column's
      // upstream lineage. The backend will push a fresh payload with
      // column_edges populated; React's lineageTrace memo paints the
      // orange dashed lines.
      window.kotlinCallback(
        JSON.stringify({ event: "COLUMN_CLICK", unique_id: uniqueId, column }),
      );
      setComputingFor({ unique_id: uniqueId, column });
    }
  }, []);

  const onOpenFile = useCallback((uniqueId: string) => {
    // Set the focus locally first so the orange ring shows up
    // immediately. The IDE eventually echoes this back via
    // setSelectedModel after it opens the file, but the round-trip
    // takes a moment — without this local set the user has to click
    // twice to see the selection.
    setSelectedModelUid(uniqueId);
    if (!window.kotlinCallback) return;
    window.kotlinCallback(JSON.stringify({ event: "NODE_CLICK", unique_id: uniqueId }));
  }, []);

  const allExpanded =
    payload.models.length > 0 && payload.models.every((m) => expanded.has(m.unique_id));

  const onToggleAllExpanded = useCallback(() => {
    if (allExpanded) {
      setExpandAllSticky(false);
      setExpanded(new Set());
    } else {
      setExpandAllSticky(true);
      setExpanded(new Set(payload.models.map((m) => m.unique_id)));
      // Mirror the single-card collapse+expand semantics: any model with
      // empty columns gets a force-retry, evicting it from attemptedColumns
      // and re-issuing REQUEST_COLUMNS. Without this, models that failed
      // an earlier prefetch (e.g. before a Python interpreter was
      // configured) stay empty when the toolbar "expand all" is used —
      // surprising vs the per-card expand behavior.
      for (const m of payload.models) {
        if (m.columns.length === 0) {
          requestColumnsIfNeeded(m.unique_id, { force: true });
        }
      }
    }
  }, [allExpanded, payload.models, requestColumnsIfNeeded]);

  // ---- Manual node positions (drag override) -------------------------------
  // Two layers:
  //   - manualPositions: persisted across renders, set on drag stop
  //   - livePositions: in-flight during drag (xyflow `onNodesChange` feeds
  //     position changes here so the card follows the cursor in real time)
  // Final node position = livePositions ?? manualPositions ?? dagre.
  // Cleared whenever DAG topology changes — those drags wouldn't carry
  // meaning to a different node set.
  const [manualPositions, setManualPositions] = useState<Record<string, { x: number; y: number }>>({});
  const [livePositions, setLivePositions] = useState<Record<string, { x: number; y: number }>>({});

  const onNodeDragStop = useCallback(
    (_e: React.MouseEvent | unknown, node: Node) => {
      setManualPositions((prev) => ({
        ...prev,
        [node.id]: { x: node.position.x, y: node.position.y },
      }));
      setLivePositions((prev) => {
        if (!(node.id in prev)) return prev;
        const next = { ...prev };
        delete next[node.id];
        return next;
      });
    },
    [],
  );

  const onResetLayout = useCallback(() => {
    setManualPositions({});
    setLivePositions({});
  }, []);
  const hasManualPositions = Object.keys(manualPositions).length > 0;

  // ---- Column lineage trace ------------------------------------------------
  // Ancestors ∪ descendants of the selected column. Two strictly-directional
  // passes — bidirectional BFS would overreach into sibling columns that
  // share an upstream/downstream node with the seed.
  const lineageTrace = useMemo(
    () => buildColumnLineageTrace(
      selectedColumn,
      payload.column_edges,
      payload.model_edges,
    ),
    [selectedColumn, payload.column_edges, payload.model_edges],
  );

  useEffect(() => {
    if (!selectedColumn) return;
    setExpanded((prev) => {
      let changed = false;
      const next = new Set(prev);
      for (const uid of lineageTrace.models) {
        if (!next.has(uid)) {
          next.add(uid);
          changed = true;
        }
      }
      return changed ? next : prev;
    });
  }, [selectedColumn, lineageTrace.models]);

  const modelTrace = useMemo(
    () => buildModelTrace(selectedModelUid, payload.model_edges),
    [selectedModelUid, payload.model_edges],
  );

  const columnTraceEdgePairs = useMemo(
    () => buildColumnTraceEdgePairs(
      selectedColumn,
      payload.column_edges,
      lineageTrace.edges,
      payload.model_edges,
      lineageTrace.models,
    ),
    [selectedColumn, payload.column_edges, lineageTrace.edges, payload.model_edges, lineageTrace.models],
  );

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
        highlightedColumns:
          lineageTrace.columns.get(m.unique_id) ?? EMPTY_HIGHLIGHTED_COLUMNS,
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

  // Per-(uid, side) flag: does the active trace continue *through*
  // this visible model into a hidden neighbour on this side? Drives
  // the ghost card's highlight + the dashed edge's colour so "the
  // trace continues into hidden territory" reads as continuation,
  // not just decoration.
  //
  // Column-trace (selectedColumn): on-trace iff a column edge in
  //   lineageTrace.edges has one endpoint on this model and the
  //   other endpoint outside payload.models.
  // Model-trace (selectedModelUid only): on-trace iff this model is
  //   on the seed's lineage tree on the same side as the ghost —
  //   the seed's own hidden parents/children, an ancestor's hidden
  //   parents (further upstream), and a descendant's hidden children
  //   (further downstream) all continue the trace.
  // Column takes precedence (mirrors the model-edge `onPath` logic).
  const traceTouchesHidden = useMemo(() => {
    const map = new Map<string, { upstream: boolean; downstream: boolean }>();
    if (selectedColumn) {
      const visible = new Set(payload.models.map((m) => m.unique_id));
      const get = (uid: string) => {
        let entry = map.get(uid);
        if (!entry) {
          entry = { upstream: false, downstream: false };
          map.set(uid, entry);
        }
        return entry;
      };
      for (const ce of payload.column_edges) {
        if (!lineageTrace.edges.has(edgeKey(ce))) continue;
        const srcVisible = visible.has(ce.source_unique_id);
        const tgtVisible = visible.has(ce.target_unique_id);
        if (srcVisible && !tgtVisible) {
          get(ce.source_unique_id).downstream = true;
        } else if (!srcVisible && tgtVisible) {
          get(ce.target_unique_id).upstream = true;
        }
      }
      return map;
    }
    if (selectedModelUid) {
      for (const m of payload.models) {
        const isSeed = m.unique_id === selectedModelUid;
        const isAncestor = modelTrace.ancestors.has(m.unique_id);
        const isDescendant = modelTrace.descendants.has(m.unique_id);
        const upstreamOnPath = isSeed || isAncestor;
        const downstreamOnPath = isSeed || isDescendant;
        if (upstreamOnPath || downstreamOnPath) {
          map.set(m.unique_id, {
            upstream: upstreamOnPath,
            downstream: downstreamOnPath,
          });
        }
      }
      return map;
    }
    return map;
  }, [
    selectedColumn,
    payload.column_edges,
    payload.models,
    lineageTrace.edges,
    selectedModelUid,
    modelTrace,
  ]);

  // Synthetic placeholder cards for "+N hidden upstream / downstream
  // models" — every visible model with a non-zero hidden_upstream
  // (or _downstream) gets a small dashed ghost card placed on the
  // matching side, connected by a dashed model edge. Lets the user
  // tell "no lineage in this direction" apart from "lineage exists
  // but is hidden by hops" without clicking anything.
  const ghostNodes: Array<Node<HiddenNeighboursGhostData, "dbtGhost">> = useMemo(() => {
    const ghosts: Array<Node<HiddenNeighboursGhostData, "dbtGhost">> = [];
    for (const m of payload.models) {
      const up = m.hidden_upstream ?? 0;
      const down = m.hidden_downstream ?? 0;
      const touched = traceTouchesHidden.get(m.unique_id);
      if (up > 0) {
        ghosts.push({
          id: ghostId(m.unique_id, "upstream"),
          type: "dbtGhost" as const,
          position: { x: 0, y: 0 },
          // zIndex: -1 puts ghost cards behind real cards so a ghost
          // landing near a real card never visually competes for the
          // foreground. xyflow defaults real nodes to 0.
          zIndex: -1,
          data: {
            count: up,
            side: "upstream",
            onTracePath: touched?.upstream ?? false,
            theme,
            cardWidth: NODE_WIDTH,
          },
        });
      }
      if (down > 0) {
        ghosts.push({
          id: ghostId(m.unique_id, "downstream"),
          type: "dbtGhost" as const,
          position: { x: 0, y: 0 },
          zIndex: -1,
          data: {
            count: down,
            side: "downstream",
            onTracePath: touched?.downstream ?? false,
            theme,
            cardWidth: NODE_WIDTH,
          },
        });
      }
    }
    return ghosts;
  }, [payload.models, traceTouchesHidden, theme]);

  const ghostEdgesForLayout = useMemo(() => {
    const out: Array<{ source_unique_id: string; target_unique_id: string }> = [];
    for (const m of payload.models) {
      if ((m.hidden_upstream ?? 0) > 0) {
        out.push({
          source_unique_id: ghostId(m.unique_id, "upstream"),
          target_unique_id: m.unique_id,
        });
      }
      if ((m.hidden_downstream ?? 0) > 0) {
        out.push({
          source_unique_id: m.unique_id,
          target_unique_id: ghostId(m.unique_id, "downstream"),
        });
      }
    }
    return out;
  }, [payload.models]);

  const heights: Record<string, number> = useMemo(() => {
    const h: Record<string, number> = {};
    for (const m of payload.models) {
      const nameLines = Math.max(1, Math.ceil(m.name.length / CHARS_PER_NAME_LINE));
      const headerH = HEADER_BASE_HEIGHT + (nameLines - 1) * NAME_LINE_HEIGHT;
      let colsH = 0;
      if (expanded.has(m.unique_id)) {
        for (const col of m.columns) {
          const nameRowLines = Math.max(
            1,
            Math.ceil(col.name.length / COLUMN_NAME_CHARS_PER_LINE),
          );
          const typeRowLines = col.type
            ? Math.max(1, Math.ceil(col.type.length / COLUMN_TYPE_CHARS_PER_LINE))
            : 1;
          const lines = Math.max(nameRowLines, typeRowLines);
          colsH += lines * COLUMN_LINE_HEIGHT + COLUMN_ROW_PADDING;
        }
        colsH += COLS_VERTICAL_PADDING;
        if (m.columns.length > COLUMN_SCROLL_THRESHOLD) {
          colsH = Math.min(colsH, COLUMN_LIST_MAX_HEIGHT + COLS_VERTICAL_PADDING);
        }
      }
      h[m.unique_id] = headerH + colsH;
    }
    for (const g of ghostNodes) {
      h[g.id] = HIDDEN_GHOST_HEIGHT;
    }
    return h;
  }, [payload, expanded, ghostNodes]);

  const [positions, setPositions] = useState<Record<string, { x: number; y: number }>>({});

  // Content-derived layout key. The 3 streamed payloads from a single
  // column click each have a new identity (kotlin pushes basePayload.copy
  // with only column_edges differing), but their topology + heights are
  // identical across the trio. Depending on payload/heights *identity*
  // would re-run ELK 3 times per click for no benefit; depending on this
  // content key short-circuits the trailing 2 runs.
  const layoutInputKey = useMemo(() => {
    const topo =
      payload.models.map((m) => m.unique_id).sort().join("|") +
      ";" +
      payload.model_edges
        .map((e) => `${e.source_unique_id}->${e.target_unique_id}`)
        .sort()
        .join("|") +
      ";" +
      ghostEdgesForLayout
        .map((e) => `${e.source_unique_id}->${e.target_unique_id}`)
        .sort()
        .join("|");
    const heightVals = Object.keys(heights)
      .sort()
      .map((k) => `${k}=${heights[k]}`)
      .join(",");
    return `${topo}/${heightVals}`;
  }, [payload.models, payload.model_edges, ghostEdgesForLayout, heights]);

  useEffect(() => {
    let cancelled = false;
    // Layout only depends on topology (ids + edges) and per-node heights.
    // Build a minimal Node[] for layout — node `data` (highlight, selection,
    // expansion) is irrelevant to positioning. Re-running layout when only
    // display state changes would shift coordinates on every column click.
    const layoutNodes: Node[] = [
      ...payload.models.map((m) => ({
        id: m.unique_id,
        position: { x: 0, y: 0 },
        data: {},
      })),
      ...ghostNodes.map((g) => ({
        id: g.id,
        position: { x: 0, y: 0 },
        data: {},
      })),
    ];
    const layoutEdges: Edge[] = [
      ...modelLevelEdges(payload),
      ...ghostEdgesForLayout.map((e) => ({
        id: `m:${e.source_unique_id}->${e.target_unique_id}`,
        source: e.source_unique_id,
        target: e.target_unique_id,
      })),
    ];
    layoutModelGraph(layoutNodes, layoutEdges, {
      rankdir: "LR",
      nodeWidth: NODE_WIDTH,
      nodeSpacing: 60,
      layerSpacing: 60,
      heights,
    }).then((result) => {
      if (cancelled) return;
      const next: Record<string, { x: number; y: number }> = {};
      for (const n of result.nodes) next[n.id] = n.position;
      setPositions(next);
    });
    return () => {
      cancelled = true;
    };
    // payload and heights are read from closure; layoutInputKey captures
    // their content so we re-run only on content change, not identity.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [layoutInputKey]);

  const derivedNodes: Node[] = useMemo(() => {
    const all: Array<Node<DbtModelNodeData, "dbtModel"> | Node<HiddenNeighboursGhostData, "dbtGhost">> = [
      ...rawNodes,
      ...ghostNodes,
    ];
    return all.map((n) => {
      const manual = manualPositions[n.id];
      const auto = positions[n.id];
      // If a node has no position yet (fresh from a hop change, ELK still
      // resolving), hide it rather than flashing it at the origin.
      if (!manual && !auto) {
        return {
          ...n,
          hidden: true,
          width: NODE_WIDTH,
          height: heights[n.id] ?? 60,
        };
      }
      return {
        ...n,
        position: manual ?? auto!,
        width: NODE_WIDTH,
        height: heights[n.id] ?? 60,
      };
    });
  }, [rawNodes, ghostNodes, positions, manualPositions, heights]);

  // What ReactFlow actually renders: derivedNodes overlaid with any
  // in-flight drag positions. No separate state — derivedNodes is the
  // source of truth, so changes to data (theme, expanded, lineage trace)
  // propagate on the same render that triggered them, instead of waiting
  // for a separate useEffect to sync a duplicate liveNodes state.
  const nodes: Node[] = useMemo(() => {
    if (Object.keys(livePositions).length === 0) return derivedNodes;
    return derivedNodes.map((n) => {
      const live = livePositions[n.id];
      return live ? { ...n, position: live } : n;
    });
  }, [derivedNodes, livePositions]);

  const onNodesChange = useCallback((changes: NodeChange[]) => {
    // We only trap *position* changes from xyflow (drag in progress).
    // Selection / dimension / data updates flow through derivedNodes via
    // React state instead.
    let dragSeen = false;
    const positionUpdates: Record<string, { x: number; y: number }> = {};
    for (const c of changes) {
      if (c.type === "position" && c.position) {
        dragSeen = true;
        positionUpdates[c.id] = { x: c.position.x, y: c.position.y };
      }
    }
    if (dragSeen) {
      setLivePositions((prev) => ({ ...prev, ...positionUpdates }));
    }
  }, []);

  // Name shown on the right of the toolbar. Hover is now handled
  // in-canvas by DbtModelNode's NodeToolbar, so this only reflects the
  // sticky orange-ring selection.
  const focusedModelName = useMemo(() => {
    if (!selectedModelUid) return null;
    return payload.models.find((m) => m.unique_id === selectedModelUid)?.name ?? null;
  }, [selectedModelUid, payload.models]);

  const edges: Edge[] = useMemo(() => {
    const onColumnTreePath = (a: string, b: string): boolean =>
      columnTraceEdgePairs.has(`${a}|${b}`);

    const onPath = (a: string, b: string): boolean =>
      selectedColumn
        ? onColumnTreePath(a, b)
        : isEdgeOnModelTreePath(a, b, selectedModelUid, modelTrace);

    // Halo colour used by [CurvedBezierEdge] at crossings to produce a
    // rope-over-rope over/under look. The base is the canvas
    // background so the halo blends with the panel; the `CC` 8-bit
    // alpha suffix (≈ 80%) lets the underlying edge fade through
    // instead of being hard-cut at the crossing, which reads as a
    // softer hierarchy rather than a sharp gap.
    const haloData = { haloColor: `${theme.background}CC` };

    const modelEdges: Edge[] = payload.model_edges.map((me) => ({
      id: `m:${me.source_unique_id}->${me.target_unique_id}`,
      source: me.source_unique_id,
      target: me.target_unique_id,
      // [CurvedBezierEdge]: same form as React Flow's default bezier,
      // longer control arms (α = 0.7 instead of 0.5) so the curve
      // bows more. Pure function of live source/target handle
      // positions, so dragging a card and moving it back perfectly
      // restores the original shape.
      type: "curved",
      data: haloData,
      style: {
        stroke: onPath(me.source_unique_id, me.target_unique_id) ? theme.edgeHighlight : theme.edge,
        strokeWidth: 1.5,
      },
    }));

    const columnEdges: Edge[] = selectedColumn
      ? payload.column_edges
          .filter((ce) => lineageTrace.edges.has(edgeKey(ce)))
          .map((ce) => ({
            id: `c:${edgeKey(ce)}`,
            source: ce.source_unique_id,
            target: ce.target_unique_id,
            type: "curved",
            data: haloData,
            label: ce.expression ? "ƒ" : undefined,
            labelStyle: { fontSize: 10, fill: theme.highlightText },
            labelBgStyle: { fill: theme.codeBg, fillOpacity: 0.9 },
            style: { stroke: theme.edgeHighlight, strokeWidth: 2, strokeDasharray: "6 3" },
            animated: true,
          }))
      : [];

    // Dashed edge from each ghost placeholder to its real model.
    // Reuses the curved edge type so it follows the same bezier
    // shape as model edges and behaves consistently when cards get
    // dragged. Goes yellow + opaque when the active column trace
    // actually crosses into a hidden neighbour on this side, so
    // the dashed line reads as continuation rather than decoration.
    const ghostEdgeList: Edge[] = ghostEdgesForLayout.map((e) => {
      // Determine which real model + side this ghost edge belongs to.
      // Upstream ghost: source is the ghost (id starts with __hidden__),
      //                 target is the real uid → highlight when target
      //                 has trace touching upstream side.
      // Downstream ghost: source is the real uid, target is the ghost.
      const ghostIsSource = e.source_unique_id.startsWith("__hidden__");
      const realUid = ghostIsSource ? e.target_unique_id : e.source_unique_id;
      const side: "upstream" | "downstream" = ghostIsSource ? "upstream" : "downstream";
      const onTrace = traceTouchesHidden.get(realUid)?.[side] ?? false;
      return {
        id: `gh:${e.source_unique_id}->${e.target_unique_id}`,
        source: e.source_unique_id,
        target: e.target_unique_id,
        type: "curved",
        data: haloData,
        // zIndex: -1 keeps ghost edges under real model + column
        // edges so they never visually obscure live trace lines, even
        // when an on-trace ghost edge crosses a busy area of the DAG.
        zIndex: -1,
        style: {
          stroke: onTrace ? theme.edgeHighlight : theme.edge,
          strokeWidth: onTrace ? 2 : 1.5,
          strokeDasharray: "4 4",
          opacity: onTrace ? 0.95 : 0.5,
        },
      };
    });

    return [...modelEdges, ...ghostEdgeList, ...columnEdges];
  }, [
    payload,
    lineageTrace,
    modelTrace,
    columnTraceEdgePairs,
    selectedColumn,
    selectedModelUid,
    ghostEdgesForLayout,
    traceTouchesHidden,
    theme,
  ]);

  // Re-fit only on topology change (model set / edge set), not on selection
  // changes. Without this, every editor selection inside the same DAG would
  // jolt the layout — the dbt Power User UX explicitly avoids that.
  const topologyKey = useMemo(() => {
    const uids = payload.models.map((m) => m.unique_id).sort().join("|");
    const edges = payload.model_edges
      .map((e) => `${e.source_unique_id}->${e.target_unique_id}`)
      .sort()
      .join("|");
    return `${uids};${edges}`;
  }, [payload.models, payload.model_edges]);

  const [fitKey, setFitKey] = useState(0);

  // Reset manual / live positions on topology change — previous drag
  // positions are not meaningful for the new node set.
  useEffect(() => {
    setManualPositions({});
    setLivePositions({});
  }, [topologyKey]);

  // Detect when ELK has produced positions for every node in the current
  // topology. Used to defer fitView until the layout actually has all
  // nodes — bumping fitKey too early fits the viewport to a partial set
  // and the nodes that arrive late fall outside the visible area.
  const positionsCompleteForTopology = useMemo(() => {
    if (payload.models.length === 0) return false;
    return payload.models.every((m) => positions[m.unique_id] !== undefined);
  }, [payload.models, positions]);

  // Re-fit ONLY once per topology, and only after positions are ready.
  // lastFittedTopology guards against re-firing on subsequent position
  // updates within the same topology (e.g. height changes from column
  // expand).
  const [lastFittedTopology, setLastFittedTopology] = useState<string>("");
  useEffect(() => {
    if (positionsCompleteForTopology && topologyKey !== lastFittedTopology) {
      setFitKey((k) => k + 1);
      setLastFittedTopology(topologyKey);
    }
  }, [positionsCompleteForTopology, topologyKey, lastFittedTopology]);

  const isEmpty = payload.models.length === 0;

  return (
    <div
      style={{
        width: "100vw",
        height: "100vh",
        display: "flex",
        flexDirection: "column",
        background: theme.background,
        color: theme.toolbarText,
      }}
    >
      <Toolbar
        theme={theme}
        upHops={upHops}
        downHops={downHops}
        onUpHops={onUpHops}
        onDownHops={onDownHops}
        onRefresh={onRefresh}
        allExpanded={allExpanded}
        onToggleAllExpanded={onToggleAllExpanded}
        hasManualPositions={hasManualPositions}
        onResetLayout={onResetLayout}
        selected={selectedColumn}
        onClear={() => setSelectedColumn(null)}
        traceCount={lineageTrace.edges.size}
        computing={
          !!(computingFor &&
             selectedColumn &&
             computingFor.unique_id === selectedColumn.unique_id &&
             computingFor.column === selectedColumn.column)
        }
        focusedModelName={focusedModelName}
        parsingCount={pendingColumns.size}
      />
      {payload.warning && <WarningBanner theme={theme} message={payload.warning} />}
      <div style={{ flex: 1, position: "relative" }}>
        {isEmpty ? (
          <EmptyState theme={theme} insidePlugin={isInsidePlugin} />
        ) : (
          <ReactFlow
            key={fitKey}
            nodes={nodes}
            edges={edges}
            onNodesChange={onNodesChange}
            nodeTypes={NODE_TYPES}
            edgeTypes={EDGE_TYPES}
            onNodeDragStop={onNodeDragStop}
            // Default is 1px, which counts a click as a drag the moment
            // the user's hand jitters by a single pixel — and React
            // Flow swallows the click event in that case. Bumping the
            // threshold lets a 4px wiggle still register as a click.
            nodeDragThreshold={4}
            fitView
            minZoom={0.05}
            maxZoom={4}
            colorMode={theme.name}
            proOptions={{ hideAttribution: true }}
          >
            <Background gap={16} color={theme.panelBorder} />
            <MiniMap
              pannable
              zoomable
              style={{ background: theme.miniMapBg, width: 140, height: 90 }}
              maskColor={theme.miniMapMask}
              nodeColor={(n) => {
                const data = n.data as DbtModelNodeData | undefined;
                return theme.layers[normalizeLayer(data?.layer)].chip;
              }}
            />
            <Controls position="bottom-right" />
            <KeyboardShortcuts />
          </ReactFlow>
        )}
      </div>
    </div>
  );
}

function EmptyState({ theme, insidePlugin }: { theme: Theme; insidePlugin: boolean }) {
  return (
    <div
      style={{
        display: "flex",
        height: "100%",
        alignItems: "center",
        justifyContent: "center",
        textAlign: "center",
        color: theme.toolbarTextMuted,
        padding: 32,
        fontFamily: "ui-sans-serif, system-ui, -apple-system, sans-serif",
        fontSize: 13,
        lineHeight: 1.6,
      }}
    >
      {insidePlugin ? (
        <div style={{ maxWidth: 380 }}>
          <div style={{ fontSize: 14, fontWeight: 600, color: theme.toolbarText, marginBottom: 6 }}>
            No lineage to display
          </div>
          Open a model <code>.sql</code> file under your dbt project's <code>models/</code> folder
          to see its lineage. If nothing happens after opening one, run{" "}
          <code>dbt parse</code> to generate <code>target/manifest.json</code>.
        </div>
      ) : (
        <div>Loading demo fixture…</div>
      )}
    </div>
  );
}

function Toolbar({
  theme,
  upHops,
  downHops,
  onUpHops,
  onDownHops,
  onRefresh,
  allExpanded,
  onToggleAllExpanded,
  hasManualPositions,
  onResetLayout,
  selected,
  onClear,
  traceCount,
  computing,
  focusedModelName,
  parsingCount,
}: {
  theme: Theme;
  upHops: number;
  downHops: number;
  onUpHops: (n: number) => void;
  onDownHops: (n: number) => void;
  onRefresh: () => void;
  allExpanded: boolean;
  onToggleAllExpanded: () => void;
  hasManualPositions: boolean;
  onResetLayout: () => void;
  selected: { unique_id: string; column: string } | null;
  onClear: () => void;
  traceCount: number;
  computing: boolean;
  /** Name of the currently selected model, or null if nothing is selected. */
  focusedModelName: string | null;
  /** Number of model uids currently waiting on a sidecar column-list response. */
  parsingCount: number;
}) {
  const t = theme;
  const buttonStyle: React.CSSProperties = {
    border: `1px solid ${t.buttonBorder}`,
    background: t.buttonBg,
    color: t.toolbarText,
    padding: "3px 10px",
    borderRadius: 6,
    cursor: "pointer",
    fontSize: 12,
  };
  const iconButtonStyle: React.CSSProperties = {
    ...buttonStyle,
    width: 28,
    height: 26,
    padding: 0,
    display: "inline-flex",
    alignItems: "center",
    justifyContent: "center",
    fontSize: 14,
  };
  return (
    <div
      style={{
        padding: "6px 12px",
        background: t.toolbarBg,
        borderBottom: `1px solid ${t.panelBorder}`,
        display: "flex",
        alignItems: "center",
        gap: 14,
        fontFamily: "ui-sans-serif, system-ui, -apple-system, sans-serif",
        fontSize: 13,
        color: t.toolbarText,
        // Fixed height tall enough for the focused-model name to wrap
        // to two lines without growing the toolbar. Everything else
        // (HopSteppers, hint text) centres vertically inside this
        // taller bar. Names longer than two lines are clipped — the
        // full text stays in the DOM for tooltip + select-copy.
        minHeight: 44,
        boxSizing: "border-box",
      }}
    >
      <div
        style={{
          display: "flex",
          flexDirection: "column",
          alignItems: "flex-start",
          lineHeight: 1.1,
        }}
      >
        <strong style={{ color: t.toolbarText }}>dbtree</strong>
        <span
          style={{ color: t.toolbarTextSubtle, fontSize: 8 }}
          title="Build timestamp"
        >
          b{__DBTREE_BUILD_ID__}
        </span>
      </div>
      <div style={{ display: "flex", alignItems: "center", gap: 10 }}>
        <HopStepper label="↑" value={upHops} onChange={onUpHops} theme={t} />
        <HopStepper label="↓" value={downHops} onChange={onDownHops} theme={t} />
        <button
          type="button"
          onClick={onToggleAllExpanded}
          title={allExpanded ? "Collapse all column lists" : "Expand all column lists"}
          style={iconButtonStyle}
        >
          {allExpanded ? "▴" : "▾"}
        </button>
        <button
          type="button"
          onClick={onRefresh}
          title="Re-read manifest.json"
          style={iconButtonStyle}
        >
          ↻
        </button>
        {hasManualPositions && (
          <button
            type="button"
            onClick={onResetLayout}
            title="Reset manually-dragged positions to auto-layout"
            style={{
              ...buttonStyle,
              fontSize: 11,
              padding: "3px 8px",
            }}
          >
            ↺ reset layout
          </button>
        )}
        {parsingCount > 0 && (
          <span
            title={`Parsing column lists for ${parsingCount} model${parsingCount === 1 ? "" : "s"} via sqlglot…`}
            style={{
              display: "inline-flex",
              alignItems: "center",
              gap: 6,
              color: t.toolbarTextMuted,
              fontFamily: "ui-sans-serif, system-ui, -apple-system, sans-serif",
              fontSize: 11,
              fontVariantNumeric: "tabular-nums",
            }}
          >
            <span className="dbtree-spinner" aria-hidden />
            <span>parsing {parsingCount}</span>
          </span>
        )}
      </div>
      <span style={{ flex: 1 }} />
      {selected ? (
        <>
          <span style={{ color: t.toolbarTextMuted }}>
            tracing{" "}
            <code
              style={{
                background: t.codeBg,
                color: t.highlightText,
                padding: "1px 6px",
                borderRadius: 4,
                fontFamily: "ui-monospace, SFMono-Regular, monospace",
              }}
            >
              {selected.unique_id.split(".").pop()}.{selected.column}
            </code>
            {" — "}
            {computing && traceCount === 0 ? (
              <em style={{ color: t.toolbarTextMuted, fontStyle: "italic" }}>
                computing column lineage…
              </em>
            ) : computing ? (
              // Streaming sidecar emits edges progressively. Show the
              // running count alongside an italic "(computing…)" suffix
              // so the user sees forward motion without losing sight of
              // the fact that more edges may still arrive.
              <>
                {traceCount} column edge{traceCount === 1 ? "" : "s"}{" "}
                <em style={{ color: t.toolbarTextMuted, fontStyle: "italic" }}>
                  (computing…)
                </em>
              </>
            ) : (
              <>
                {traceCount} column edge{traceCount === 1 ? "" : "s"}
              </>
            )}
          </span>
          <button type="button" onClick={onClear} style={buttonStyle}>
            clear
          </button>
        </>
      ) : focusedModelName ? (
        <span
          style={{
            color: t.toolbarText,
            fontFamily: "ui-monospace, SFMono-Regular, monospace",
            fontSize: 12,
            lineHeight: 1.3,
            // Allow wrapping up to two lines; the parent toolbar's
            // minHeight already reserves room for two lines so the
            // height never jitters between one- and two-line names.
            // Anything beyond two lines is clipped (full text in DOM
            // via tooltip + selection).
            maxWidth: 480,
            maxHeight: "2.6em",
            overflow: "hidden",
            overflowWrap: "anywhere",
            wordBreak: "break-all",
            textAlign: "right",
            userSelect: "text",
            cursor: "text",
          }}
          title={focusedModelName}
        >
          {focusedModelName}
        </span>
      ) : (
        <span style={{ color: t.toolbarTextSubtle }}>
          {(isUnlimited(upHops) ? "∞" : upHops)} up · {(isUnlimited(downHops) ? "∞" : downHops)} down · click model name to open
        </span>
      )}
    </div>
  );
}

function WarningBanner({ theme, message }: { theme: Theme; message: string }) {
  // Lifted from highlight token so it visually ties to the orange "selected"
  // / "edge highlight" theme without inventing new colors. Uses an
  // alert-icon prefix so screen-readers announce it as a warning.
  return (
    <div
      role="status"
      aria-live="polite"
      style={{
        display: "flex",
        alignItems: "flex-start",
        gap: 8,
        padding: "8px 14px",
        background: theme.highlightBg,
        color: theme.highlightText,
        borderBottom: `1px solid ${theme.panelBorder}`,
        fontFamily: "ui-sans-serif, system-ui, -apple-system, sans-serif",
        fontSize: 12,
        lineHeight: 1.4,
      }}
    >
      <span aria-hidden style={{ fontWeight: 700, marginTop: 1 }}>⚠</span>
      <span style={{ whiteSpace: "pre-line", fontFamily: "inherit" }}>
        {message}
      </span>
    </div>
  );
}

function modelLevelEdges(p: LineagePayload): Edge[] {
  return p.model_edges.map((me) => ({
    id: `m:${me.source_unique_id}->${me.target_unique_id}`,
    source: me.source_unique_id,
    target: me.target_unique_id,
  }));
}

export default App;
