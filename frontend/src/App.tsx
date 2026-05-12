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
import { DbtModelNode, type DbtModelNodeData } from "./components/DbtModelNode";
import { ElkRoutedEdge } from "./components/ElkRoutedEdge";
import { HopStepper, isUnlimited } from "./components/HopStepper";
import { layoutModelGraph, type EdgeRoute } from "./lib/layout";
import {
  buildColumnLineageTrace,
  buildColumnTraceEdgePairs,
  buildModelTrace,
  edgeKey,
  isEdgeOnModelTreePath,
} from "./lib/lineage-trace";
import { THEMES, detectInitialTheme, normalizeLayer, type Theme, type ThemeName } from "./lib/theme";

const NODE_TYPES: NodeTypes = { dbtModel: DbtModelNode };
const EDGE_TYPES: EdgeTypes = { elkRouted: ElkRoutedEdge };
const NODE_WIDTH = 320;
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

// Padding added to each node's height ONLY when handed to ELK. The actual
// rendered card stays at its measured height; this fudge gives ELK extra
// vertical breathing room around each card so its avoid-cards orthogonal
// route — and the Catmull-Rom curve we smooth over it — clears the card
// body even when our char-per-line height estimate slightly undershoots.
const HEIGHT_FUDGE_FOR_LAYOUT = 40;

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
      setPayload(next);
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
    };
    return () => {
      delete window.setLineageInfo;
      delete window.setSelectedModel;
      delete window.applyModelColumns;
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

  // ---- Expanded models -----------------------------------------------------
  const [expanded, setExpanded] = useState<Set<string>>(() => {
    return new Set(payload.selected ? [payload.selected.unique_id] : []);
  });

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

  // When a new full payload arrives, drop column selection if the column
  // no longer exists, and ensure the selected model is expanded.
  useEffect(() => {
    setSelectedColumn((prev) => {
      if (!prev) return prev;
      const stillExists = payload.models.some(
        (m) => m.unique_id === prev.unique_id && m.columns.some((c) => c.name === prev.column),
      );
      return stillExists ? prev : null;
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
    (uniqueId: string) => {
      if (!isInsidePlugin || !window.kotlinCallback) return;
      const model = payload.models.find((m) => m.unique_id === uniqueId);
      if (!model || model.columns.length > 0) return;
      if (pendingColumns.has(uniqueId)) return;
      setPendingColumns((prev) => {
        const next = new Set(prev);
        next.add(uniqueId);
        return next;
      });
      window.kotlinCallback(
        JSON.stringify({ event: "REQUEST_COLUMNS", unique_id: uniqueId }),
      );
    },
    [payload.models, pendingColumns],
  );

  const toggleExpanded = useCallback(
    (uniqueId: string) => {
      setExpanded((prev) => {
        const next = new Set(prev);
        if (next.has(uniqueId)) {
          next.delete(uniqueId);
        } else {
          next.add(uniqueId);
          // Going from collapsed → expanded: trigger lazy column fetch.
          // No-op if columns already populated or already fetching.
          requestColumnsIfNeeded(uniqueId);
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
    if (!window.kotlinCallback) return;
    window.kotlinCallback(JSON.stringify({ event: "NODE_CLICK", unique_id: uniqueId }));
  }, []);

  const allExpanded =
    payload.models.length > 0 && payload.models.every((m) => expanded.has(m.unique_id));

  const onToggleAllExpanded = useCallback(() => {
    setExpanded(() => {
      if (allExpanded) return new Set();
      return new Set(payload.models.map((m) => m.unique_id));
    });
  }, [allExpanded, payload.models]);

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
    () => buildColumnLineageTrace(selectedColumn, payload.column_edges),
    [selectedColumn, payload.column_edges],
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
    () => buildColumnTraceEdgePairs(selectedColumn, payload.column_edges, lineageTrace.edges),
    [selectedColumn, payload.column_edges, lineageTrace.edges],
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
      }
      h[m.unique_id] = headerH + colsH;
    }
    return h;
  }, [payload, expanded]);

  const [positions, setPositions] = useState<Record<string, { x: number; y: number }>>({});
  // ELK-computed routes per model edge id. Empty when the dagre engine is
  // in use (dagre doesn't expose per-edge routing). Consumed by the
  // [ElkRoutedEdge] custom edge to draw a smooth curve through ELK's
  // avoid-cards bend points.
  const [edgeRoutes, setEdgeRoutes] = useState<Map<string, EdgeRoute>>(
    () => new Map(),
  );

  useEffect(() => {
    let cancelled = false;
    // Layout only depends on topology (ids + edges) and per-node heights.
    // Build a minimal Node[] for layout — node `data` (highlight, selection,
    // expansion) is irrelevant to positioning. Re-running layout when only
    // display state changes would shift coordinates on every column click.
    const layoutNodes: Node[] = payload.models.map((m) => ({
      id: m.unique_id,
      position: { x: 0, y: 0 },
      data: {},
    }));
    // Pad each node's height before passing to ELK so its avoid-cards
    // routing leaves more vertical clearance around each card. The
    // rendered card keeps its real height — only the layout engine sees
    // the inflated number. See [HEIGHT_FUDGE_FOR_LAYOUT].
    const fudgedHeights: Record<string, number> = {};
    for (const [id, h] of Object.entries(heights)) {
      fudgedHeights[id] = h + HEIGHT_FUDGE_FOR_LAYOUT;
    }
    layoutModelGraph(layoutNodes, modelLevelEdges(payload), {
      rankdir: "LR",
      nodeWidth: NODE_WIDTH,
      nodesepX: 60,
      ranksepY: 100,
      heights: fudgedHeights,
    }).then((result) => {
      if (cancelled) return;
      const next: Record<string, { x: number; y: number }> = {};
      for (const n of result.nodes) next[n.id] = n.position;
      setPositions(next);
      setEdgeRoutes(result.edgeRoutes);
    });
    return () => {
      cancelled = true;
    };
  }, [payload, heights]);

  const derivedNodes: Node[] = useMemo(() => {
    return rawNodes.map((n) => {
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
  }, [rawNodes, positions, manualPositions, heights]);

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

  const edges: Edge[] = useMemo(() => {
    const onColumnTreePath = (a: string, b: string): boolean =>
      columnTraceEdgePairs.has(`${a}|${b}`);

    const onPath = (a: string, b: string): boolean =>
      selectedColumn
        ? onColumnTreePath(a, b)
        : isEdgeOnModelTreePath(a, b, selectedModelUid, modelTrace);

    const modelEdges: Edge[] = payload.model_edges.map((me) => {
      const id = `m:${me.source_unique_id}->${me.target_unique_id}`;
      const route = edgeRoutes.get(id);
      return {
        id,
        source: me.source_unique_id,
        target: me.target_unique_id,
        // Custom edge that smooths ELK's bendPoints into a curve. The
        // edge type is registered on every model edge regardless of
        // whether ELK produced a route; [ElkRoutedEdge] falls back to
        // React Flow's default bezier path when `route` is absent.
        type: "elkRouted",
        data: route ? { route } : undefined,
        style: {
          stroke: onPath(me.source_unique_id, me.target_unique_id) ? theme.edgeHighlight : theme.edge,
          strokeWidth: 1.5,
        },
      };
    });

    const columnEdges: Edge[] = selectedColumn
      ? payload.column_edges
          .filter((ce) => lineageTrace.edges.has(edgeKey(ce)))
          .map((ce) => {
            // Column edges aren't part of the ELK-laid graph (model-level
            // only), so they never have a route — [ElkRoutedEdge] will
            // render them with the default bezier fallback. Still routed
            // through the custom edge type so they share the rendering
            // pipeline.
            return {
              id: `c:${edgeKey(ce)}`,
              source: ce.source_unique_id,
              target: ce.target_unique_id,
              type: "elkRouted",
              label: ce.expression ? "ƒ" : undefined,
              labelStyle: { fontSize: 10, fill: theme.highlightText },
              labelBgStyle: { fill: theme.codeBg, fillOpacity: 0.9 },
              style: { stroke: theme.edgeHighlight, strokeWidth: 2, strokeDasharray: "6 3" },
              animated: true,
            };
          })
      : [];

    return [...modelEdges, ...columnEdges];
  }, [
    payload,
    lineageTrace,
    modelTrace,
    columnTraceEdgePairs,
    selectedColumn,
    selectedModelUid,
    theme,
    edgeRoutes,
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
            fitView
            minZoom={0.2}
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
      }}
    >
      <strong style={{ color: t.toolbarText }}>intellij-dbtree</strong>
      <span style={{ color: t.toolbarTextSubtle, fontSize: 10 }} title="Build timestamp">
        b{__DBTREE_BUILD_ID__}
      </span>
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
