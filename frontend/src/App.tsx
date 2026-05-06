import { useCallback, useEffect, useMemo, useState } from "react";
import {
  Background,
  Controls,
  MiniMap,
  ReactFlow,
  type Edge,
  type Node,
  type NodeTypes,
} from "@xyflow/react";
import "@xyflow/react/dist/style.css";

import demoFixture from "./fixtures/lineage-demo.json";
import type { ColumnEdge, LineagePayload } from "./types";
import { DbtModelNode, type DbtModelNodeData } from "./components/DbtModelNode";
import { layoutModelGraph } from "./lib/layout";

const NODE_TYPES: NodeTypes = { dbtModel: DbtModelNode };

const NODE_WIDTH = 240;
const HEADER_HEIGHT = 38;
const ROW_HEIGHT = 22;

// Synthetic host the JetBrains plugin uses when serving via JCEF.
// Outside that host (Vite dev server / file://) we fall back to the demo fixture
// so the standalone app still has something to render.
const PLUGIN_HOST = "intellij-dbtree.local";
const isInsidePlugin = typeof window !== "undefined" && window.location.hostname === PLUGIN_HOST;

declare global {
  interface Window {
    setLineageInfo?: (payload: LineagePayload) => void;
    kotlinCallback?: (payload: string) => void;
  }
}

const EMPTY_PAYLOAD: LineagePayload = {
  models: [],
  model_edges: [],
  column_edges: [],
};

function App() {
  // Inside the plugin we wait for Kotlin to push a payload. Standalone
  // (vite dev / static preview) we render the demo fixture so the page
  // is not empty.
  const [payload, setPayload] = useState<LineagePayload>(() =>
    isInsidePlugin ? EMPTY_PAYLOAD : (demoFixture as LineagePayload),
  );

  // Expose `window.setLineageInfo` so the plugin's executeJavaScript push works.
  useEffect(() => {
    window.setLineageInfo = (next) => setPayload(next);
    return () => {
      delete window.setLineageInfo;
    };
  }, []);

  // Which models have their column lists expanded.
  const [expanded, setExpanded] = useState<Set<string>>(() => {
    return new Set(payload.selected ? [payload.selected.unique_id] : []);
  });

  // Selected (model, column) — drives column-edge highlighting.
  const [selectedColumn, setSelectedColumn] = useState<{
    unique_id: string;
    column: string;
  } | null>(() =>
    payload.selected?.column
      ? { unique_id: payload.selected.unique_id, column: payload.selected.column }
      : null,
  );

  // When the plugin pushes a new payload, default-expand the selected model
  // and clear any column highlight that no longer applies.
  useEffect(() => {
    setExpanded((prev) => {
      if (!payload.selected) return prev;
      if (prev.has(payload.selected.unique_id)) return prev;
      const next = new Set(prev);
      next.add(payload.selected.unique_id);
      return next;
    });
    setSelectedColumn((prev) => {
      if (!prev) return prev;
      const stillExists = payload.models.some(
        (m) => m.unique_id === prev.unique_id && m.columns.some((c) => c.name === prev.column),
      );
      return stillExists ? prev : null;
    });
  }, [payload]);

  const toggleExpanded = useCallback((uniqueId: string) => {
    setExpanded((prev) => {
      const next = new Set(prev);
      if (next.has(uniqueId)) next.delete(uniqueId);
      else next.add(uniqueId);
      return next;
    });
  }, []);

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
  }, []);

  const onOpenFile = useCallback((uniqueId: string) => {
    if (!window.kotlinCallback) return;
    window.kotlinCallback(JSON.stringify({ event: "NODE_CLICK", unique_id: uniqueId }));
  }, []);

  // Trace the upstream column lineage from a (model, column) seed.
  const lineageTrace = useMemo(() => {
    const trace = {
      columns: new Map<string, Set<string>>(),
      edges: new Set<string>(),
      models: new Set<string>(),
    };
    if (!selectedColumn) return trace;

    const queue: Array<{ unique_id: string; column: string }> = [selectedColumn];
    const seen = new Set<string>();
    while (queue.length > 0) {
      const cur = queue.shift()!;
      const key = `${cur.unique_id}|${cur.column}`;
      if (seen.has(key)) continue;
      seen.add(key);

      let cols = trace.columns.get(cur.unique_id);
      if (!cols) {
        cols = new Set();
        trace.columns.set(cur.unique_id, cols);
      }
      cols.add(cur.column);
      trace.models.add(cur.unique_id);

      for (const ce of payload.column_edges) {
        if (ce.target_unique_id === cur.unique_id && ce.target_column === cur.column) {
          trace.edges.add(edgeKey(ce));
          queue.push({ unique_id: ce.source_unique_id, column: ce.source_column });
        }
      }
    }
    return trace;
  }, [selectedColumn, payload.column_edges]);

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

  const nodes: Node[] = useMemo(() => {
    const isExpanded = (uid: string) => expanded.has(uid);
    const expandedColumnCount: Record<string, number> = {};
    for (const m of payload.models) {
      expandedColumnCount[m.unique_id] = isExpanded(m.unique_id) ? m.columns.length : 0;
    }

    const rawNodes: Array<Node<DbtModelNodeData, "dbtModel">> = payload.models.map((m) => ({
      id: m.unique_id,
      type: "dbtModel" as const,
      position: { x: 0, y: 0 },
      data: {
        unique_id: m.unique_id,
        name: m.name,
        package_name: m.package_name,
        layer: m.layer,
        columns: m.columns,
        expanded: isExpanded(m.unique_id),
        highlightedColumns: lineageTrace.columns.get(m.unique_id) ?? new Set<string>(),
        onLineagePath: lineageTrace.models.has(m.unique_id),
        isSelectedModel: m.unique_id === payload.selected?.unique_id,
        onToggleExpanded: toggleExpanded,
        onColumnClick,
        onOpenFile,
      },
    }));

    return layoutModelGraph(rawNodes, modelLevelEdges(payload), {
      rankdir: "LR",
      nodeWidth: NODE_WIDTH,
      headerHeight: HEADER_HEIGHT,
      rowHeight: ROW_HEIGHT,
      nodesepX: 60,
      ranksepY: 100,
      expandedColumnCount,
    });
  }, [payload, expanded, lineageTrace, toggleExpanded, onColumnClick, onOpenFile]);

  const edges: Edge[] = useMemo(() => {
    const onPath = (a: string, b: string) =>
      lineageTrace.models.has(a) && lineageTrace.models.has(b);

    const modelEdges: Edge[] = payload.model_edges.map((me) => ({
      id: `m:${me.source_unique_id}->${me.target_unique_id}`,
      source: me.source_unique_id,
      target: me.target_unique_id,
      style: {
        stroke: onPath(me.source_unique_id, me.target_unique_id) ? "#f59e0b" : "#cbd5e1",
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
            label: ce.expression ? "ƒ" : undefined,
            labelStyle: { fontSize: 10, fill: "#92400e" },
            labelBgStyle: { fill: "#fef3c7", fillOpacity: 0.9 },
            style: { stroke: "#f59e0b", strokeWidth: 2, strokeDasharray: "6 3" },
            animated: true,
          }))
      : [];

    return [...modelEdges, ...columnEdges];
  }, [payload.model_edges, payload.column_edges, lineageTrace, selectedColumn]);

  const [fitKey, setFitKey] = useState(0);
  useEffect(() => {
    setFitKey((k) => k + 1);
  }, [payload, expanded.size, selectedColumn?.unique_id, selectedColumn?.column]);

  const isEmpty = payload.models.length === 0;

  return (
    <div style={{ width: "100vw", height: "100vh", display: "flex", flexDirection: "column" }}>
      <Toolbar
        selected={selectedColumn}
        onClear={() => setSelectedColumn(null)}
        traceCount={lineageTrace.edges.size}
      />
      <div style={{ flex: 1, position: "relative" }}>
        {isEmpty ? (
          <EmptyState insidePlugin={isInsidePlugin} />
        ) : (
          <ReactFlow
            key={fitKey}
            nodes={nodes}
            edges={edges}
            nodeTypes={NODE_TYPES}
            fitView
            minZoom={0.2}
            proOptions={{ hideAttribution: true }}
          >
            <Background gap={16} color="#e2e8f0" />
            <MiniMap pannable zoomable />
            <Controls position="bottom-right" />
          </ReactFlow>
        )}
      </div>
    </div>
  );
}

function EmptyState({ insidePlugin }: { insidePlugin: boolean }) {
  return (
    <div
      style={{
        display: "flex",
        height: "100%",
        alignItems: "center",
        justifyContent: "center",
        textAlign: "center",
        color: "#64748b",
        padding: 32,
        fontFamily: "ui-sans-serif, system-ui, -apple-system, sans-serif",
        fontSize: 13,
        lineHeight: 1.6,
      }}
    >
      {insidePlugin ? (
        <div style={{ maxWidth: 380 }}>
          <div style={{ fontSize: 14, fontWeight: 600, color: "#0f172a", marginBottom: 6 }}>
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
  selected,
  onClear,
  traceCount,
}: {
  selected: { unique_id: string; column: string } | null;
  onClear: () => void;
  traceCount: number;
}) {
  return (
    <div
      style={{
        padding: "8px 16px",
        background: "white",
        borderBottom: "1px solid #e2e8f0",
        display: "flex",
        alignItems: "center",
        gap: 12,
        fontFamily: "ui-sans-serif, system-ui, -apple-system, sans-serif",
        fontSize: 13,
      }}
    >
      <strong style={{ color: "#0f172a" }}>intellij-dbtree</strong>
      <span style={{ flex: 1 }} />
      {selected ? (
        <>
          <span style={{ color: "#64748b" }}>
            tracing{" "}
            <code style={{ background: "#fef3c7", padding: "1px 6px", borderRadius: 4 }}>
              {selected.unique_id.split(".").pop()}.{selected.column}
            </code>
            {" — "}
            {traceCount} column edge{traceCount === 1 ? "" : "s"}
          </span>
          <button
            type="button"
            onClick={onClear}
            style={{
              border: "1px solid #cbd5e1",
              background: "white",
              padding: "3px 10px",
              borderRadius: 6,
              cursor: "pointer",
              fontSize: 12,
            }}
          >
            clear
          </button>
        </>
      ) : (
        <span style={{ color: "#94a3b8" }}>click a column to trace · double-click model header to open file</span>
      )}
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

function edgeKey(ce: ColumnEdge): string {
  return `${ce.source_unique_id}|${ce.source_column}->${ce.target_unique_id}|${ce.target_column}`;
}

export default App;
