import { describe, expect, it } from "vitest";
import type { ColumnEdge, ColumnSpec, DbtModel, LineagePayload } from "../types";
import {
  applyColumnEdgesDelta,
  mergePayloadPreservingColumns,
} from "./payload-reducer";

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

const m = (uniqueId: string, columns: ColumnSpec[] = []): DbtModel => ({
  unique_id: uniqueId,
  name: uniqueId.split(".").pop() ?? uniqueId,
  package_name: "demo",
  columns,
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

describe("mergePayloadPreservingColumns", () => {
  // Background: dbt yml/catalog often doesn't list columns, so Kotlin
  // ships them as []. The Python sidecar later patches them in via
  // applyModelColumns. Without this merge, every fresh setLineageInfo
  // (model navigation, hop change, manifest reload, column-click
  // republish) wipes the patched columns back to [] and the user
  // watches every card flicker through "Parsing SQL…" again — plus
  // any publish that silently drops in the Kotlin → JCEF → React
  // chain leaves the card stuck on that placeholder forever.

  it("keeps prev columns when next has the same uid with empty columns", () => {
    const prev: LineagePayload = {
      ...basePayload(),
      models: [m("model.a", [{ name: "id" }, { name: "name" }])],
    };
    const next: LineagePayload = {
      ...basePayload(),
      models: [m("model.a", [])],
    };
    const result = mergePayloadPreservingColumns(prev, next);
    expect(result.models[0].columns).toEqual([
      { name: "id" },
      { name: "name" },
    ]);
  });

  it("uses next columns when next already populated them (yml/catalog hit)", () => {
    const prev: LineagePayload = {
      ...basePayload(),
      models: [m("model.a", [{ name: "stale" }])],
    };
    const next: LineagePayload = {
      ...basePayload(),
      models: [m("model.a", [{ name: "fresh" }, { name: "another" }])],
    };
    const result = mergePayloadPreservingColumns(prev, next);
    expect(result.models[0].columns).toEqual([
      { name: "fresh" },
      { name: "another" },
    ]);
  });

  it("drops uids that are no longer in the next payload", () => {
    const prev: LineagePayload = {
      ...basePayload(),
      models: [
        m("model.a", [{ name: "id" }]),
        m("model.b", [{ name: "x" }]),
      ],
    };
    const next: LineagePayload = {
      ...basePayload(),
      models: [m("model.a", [])],
    };
    const result = mergePayloadPreservingColumns(prev, next);
    expect(result.models).toHaveLength(1);
    expect(result.models[0].unique_id).toBe("model.a");
    expect(result.models[0].columns).toEqual([{ name: "id" }]);
  });

  it("does NOT inherit prev columns for a uid that wasn't in prev", () => {
    const prev: LineagePayload = {
      ...basePayload(),
      models: [m("model.a", [{ name: "id" }])],
    };
    const next: LineagePayload = {
      ...basePayload(),
      models: [m("model.a", []), m("model.brand_new", [])],
    };
    const result = mergePayloadPreservingColumns(prev, next);
    const brandNew = result.models.find((mo) => mo.unique_id === "model.brand_new");
    expect(brandNew?.columns).toEqual([]);
  });

  it("preserves non-models fields verbatim from next", () => {
    const prev: LineagePayload = {
      ...basePayload(),
      warning: "old warning",
    };
    const next: LineagePayload = {
      models: [m("model.a")],
      model_edges: [{ source_unique_id: "x", target_unique_id: "y" }],
      column_edges: [ce("a", "id", "b", "id")],
      selected: { unique_id: "model.a", column: undefined },
      column_lineage_done: true,
    };
    const result = mergePayloadPreservingColumns(prev, next);
    expect(result.model_edges).toBe(next.model_edges);
    expect(result.column_edges).toBe(next.column_edges);
    expect(result.selected).toBe(next.selected);
    expect(result.column_lineage_done).toBe(true);
    expect(result.warning).toBeUndefined();
  });

  it("keeps the next model object reference when no merge is needed (memo stability)", () => {
    // If a model in next already has columns, return the exact same
    // model reference — downstream memos shouldn't re-fire just because
    // we walked the list.
    const sharedModel = m("model.a", [{ name: "fresh" }]);
    const prev: LineagePayload = {
      ...basePayload(),
      models: [m("model.a", [{ name: "fresh" }])],
    };
    const next: LineagePayload = {
      ...basePayload(),
      models: [sharedModel],
    };
    const result = mergePayloadPreservingColumns(prev, next);
    expect(result.models[0]).toBe(sharedModel);
  });
});
