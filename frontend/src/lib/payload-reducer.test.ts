import { describe, expect, it } from "vitest";
import type { ColumnEdge, LineagePayload } from "../types";
import { applyColumnEdgesDelta } from "./payload-reducer";

const ce = (s: string, sc: string, t: string, tc: string): ColumnEdge => ({
  source_unique_id: s,
  source_column: sc,
  target_unique_id: t,
  target_column: tc,
});

const basePayload = (): LineagePayload => ({
  models: [],
  model_edges: [],
  column_edges: [],
});

describe("applyColumnEdgesDelta", () => {
  it("appends new edges onto existing column_edges", () => {
    const prev: LineagePayload = {
      ...basePayload(),
      column_edges: [ce("a", "x", "b", "x")],
    };
    const next = applyColumnEdgesDelta(prev, {
      append_edges: [ce("b", "x", "c", "x"), ce("c", "x", "d", "x")],
      column_lineage_done: false,
      warning: null,
    });
    expect(next.column_edges).toHaveLength(3);
    expect(next.column_edges[0].source_unique_id).toBe("a");
    expect(next.column_edges[1].target_unique_id).toBe("c");
    expect(next.column_edges[2].target_unique_id).toBe("d");
  });

  it("flips column_lineage_done on the final delta", () => {
    const prev = { ...basePayload(), column_lineage_done: false };
    const next = applyColumnEdgesDelta(prev, {
      append_edges: [],
      column_lineage_done: true,
      warning: null,
    });
    expect(next.column_lineage_done).toBe(true);
  });

  it("clears warning when delta carries null", () => {
    const prev: LineagePayload = { ...basePayload(), warning: "stale" };
    const next = applyColumnEdgesDelta(prev, {
      append_edges: [],
      column_lineage_done: true,
      warning: null,
    });
    expect(next.warning).toBeUndefined();
  });

  it("sets warning when delta carries a non-null string", () => {
    const prev = basePayload();
    const next = applyColumnEdgesDelta(prev, {
      append_edges: [],
      column_lineage_done: true,
      warning: "sqlglot fell back to placeholder for source.*",
    });
    expect(next.warning).toBe("sqlglot fell back to placeholder for source.*");
  });

  it("preserves topology field references so memos keyed on them stay stable", () => {
    // This is the whole point of the delta event: streaming column-edge
    // republishes must NOT churn payload.models / payload.model_edges,
    // because their identity drives expensive React Flow re-renders and
    // ELK relayout in App.tsx. Object.is equality must hold here.
    const models = [
      {
        unique_id: "a",
        name: "a",
        package_name: "pkg",
        columns: [],
      },
    ];
    const modelEdges = [{ source_unique_id: "a", target_unique_id: "b" }];
    const selected = { unique_id: "a", column: "x" };
    const prev: LineagePayload = {
      models,
      model_edges: modelEdges,
      column_edges: [],
      selected,
    };
    const next = applyColumnEdgesDelta(prev, {
      append_edges: [ce("a", "x", "b", "x")],
      column_lineage_done: false,
      warning: null,
    });
    expect(next.models).toBe(models);
    expect(next.model_edges).toBe(modelEdges);
    expect(next.selected).toBe(selected);
  });

  it("returns a fresh payload reference and a fresh column_edges array", () => {
    const prev = basePayload();
    const next = applyColumnEdgesDelta(prev, {
      append_edges: [],
      column_lineage_done: false,
      warning: null,
    });
    expect(next).not.toBe(prev);
    expect(next.column_edges).not.toBe(prev.column_edges);
  });
});
