/**
 * Wire format between the JetBrains plugin (Phase A) and the JCEF UI (this app).
 *
 * Phase A will produce this payload by:
 *  1. Reading dbt's manifest.json for models + parent_map (Kotlin)
 *  2. Spawning the Python sidecar (Phase C) for column-level lineage
 *  3. Stitching the column edges across models
 *
 * Phase B (now) hand-crafts a fixture in this exact shape so the UI is ready
 * to plug into Phase A without contract changes.
 */

export interface ColumnSpec {
  name: string;
  /** Optional SQL type (e.g. "VARCHAR", "NUMERIC(18,2)"). May be absent in MVP. */
  type?: string;
  /** Optional column description from dbt schema.yml. */
  description?: string;
}

export interface DbtModel {
  /** dbt unique_id, e.g. "model.my_project.fct_orders". */
  unique_id: string;
  /** Short name, e.g. "fct_orders". */
  name: string;
  package_name: string;
  /** Canonical color bucket: "staging" | "intermediate" | "marts" | "source". */
  layer?: ModelLayer;
  /**
   * Raw first folder segment of the model's `path` (e.g. "marts_msd",
   * "dash"). Shown verbatim on the model card chip; preserves dbt
   * namespacing even when [layer] collapses to one of the four canonical
   * buckets. Falls back to [layer] when absent (older Kotlin payloads).
   */
  folder?: string;
  /**
   * dbt materialization strategy from `config.materialized`:
   * "table" | "view" | "incremental" | "ephemeral" | "materialized_view"
   * | (custom adapter materialization). Rendered as a small T/V/I letter
   * badge on the card; null/unknown values render no badge. Sources
   * never carry this (no materialization concept).
   */
  materialization?: string;
  columns: ColumnSpec[];
}

export type ModelLayer = "staging" | "intermediate" | "marts" | "source";

export interface ModelEdge {
  source_unique_id: string;
  target_unique_id: string;
}

export interface ColumnEdge {
  source_unique_id: string;
  source_column: string;
  target_unique_id: string;
  target_column: string;
  /**
   * Optional SQL expression at the target side (e.g. "amount * 1.05"),
   * lifted from sqlglot's lineage tree. Useful for hover tooltips.
   */
  expression?: string;
}

export interface LineagePayload {
  models: DbtModel[];
  model_edges: ModelEdge[];
  column_edges: ColumnEdge[];
  /** The model the user opened — UI focuses layout around it. */
  selected?: {
    unique_id: string;
    column?: string;
  };
  /**
   * Optional non-fatal warning surfaced to the toolbar (e.g. "no Python
   * interpreter with sqlglot available — column lineage disabled").
   * Cleared on the next successful payload.
   */
  warning?: string;
  /**
   * False while the sidecar is still streaming column-lineage edges for
   * [selected]; flips to true on the final payload (success or failure).
   *
   * React uses this to keep the "computing column lineage…" hint visible
   * while edges trickle in, and to clear it once the stream terminates.
   *
   * Defaults to true (treated as "stream finished" for non-streaming
   * publishes — full payloads, hop changes, etc.).
   */
  column_lineage_done?: boolean;
}
