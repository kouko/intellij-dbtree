package dev.kouko.intellijdbtree.lineage

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Wire format pushed from Kotlin to the JCEF React UI.
 *
 * Mirrors `frontend/src/types.ts`'s `LineagePayload` exactly — keep these
 * in sync. The `@SerialName` overrides are needed because the React side
 * uses `snake_case` while idiomatic Kotlin is `camelCase`.
 */

@Serializable
data class ColumnSpec(
    val name: String,
    val type: String? = null,
    val description: String? = null,
)

@Serializable
data class DbtModel(
    @SerialName("unique_id") val uniqueId: String,
    val name: String,
    @SerialName("package_name") val packageName: String,
    /**
     * Canonical color bucket: "staging" | "intermediate" | "marts" | "source" | null.
     * The frontend uses this to pick chip / border / background color tokens.
     */
    val layer: String? = null,
    /**
     * Raw first folder segment of the model's path (e.g. `marts_msd`,
     * `dash`, `staging`). Shown verbatim on the model card chip so dbt
     * projects with namespaced subfolders preserve the distinction even
     * when [layer] collapses to one of the four canonical buckets.
     */
    val folder: String? = null,
    /**
     * dbt materialization strategy from `config.materialized` —
     * "table" | "view" | "incremental" | "ephemeral" | "materialized_view"
     * | other. Null for sources (no materialization concept) and for
     * models whose manifest entry omits config.
     *
     * Rendered as a small T / V / I letter badge on the model card so
     * the user can spot expensive (table / incremental) vs cheap (view)
     * objects at a glance.
     */
    val materialization: String? = null,
    val columns: List<ColumnSpec> = emptyList(),
    /**
     * Count of this model's upstream / downstream neighbours in the
     * FULL dbt graph that aren't included in the current payload (their
     * uids fell outside the seed's hop budget). The frontend renders
     * these as "+N" chips on the corresponding handle so the user can
     * tell "no lineage in this direction" apart from "lineage exists
     * but is hidden by hops". Default 0 keeps older payload consumers
     * (and pre-feature payloads) rendering normally.
     */
    @SerialName("hidden_upstream") val hiddenUpstream: Int = 0,
    @SerialName("hidden_downstream") val hiddenDownstream: Int = 0,
)

@Serializable
data class ModelEdge(
    @SerialName("source_unique_id") val sourceUniqueId: String,
    @SerialName("target_unique_id") val targetUniqueId: String,
)

@Serializable
data class ColumnEdge(
    @SerialName("source_unique_id") val sourceUniqueId: String,
    @SerialName("source_column") val sourceColumn: String,
    @SerialName("target_unique_id") val targetUniqueId: String,
    @SerialName("target_column") val targetColumn: String,
    val expression: String? = null,
)

@Serializable
data class Selected(
    @SerialName("unique_id") val uniqueId: String,
    val column: String? = null,
)

/**
 * Streaming column-edges patch. Companion to
 * [LineageInfoListener.columnEdgesAppended]: instead of re-sending the
 * whole [LineagePayload] every 500ms during a column-lineage trace
 * (which made JCEF JSON-parse a multi-MB script source on each flush
 * and snowballed React's memo cascade), the producer sends only the
 * newly-discovered edges plus the terminal flags. The React-side
 * reducer in `payload-reducer.ts` folds these onto the existing
 * payload while keeping topology fields by reference.
 */
@Serializable
data class ColumnEdgesDelta(
    @SerialName("append_edges") val appendEdges: List<ColumnEdge>,
    @SerialName("column_lineage_done") val columnLineageDone: Boolean,
    /**
     * `null` clears any prior warning (clean completion); non-null
     * replaces it. Mirrors the explicit-null contract documented on
     * the TS side.
     */
    val warning: String? = null,
)

@Serializable
data class LineagePayload(
    val models: List<DbtModel> = emptyList(),
    @SerialName("model_edges") val modelEdges: List<ModelEdge> = emptyList(),
    @SerialName("column_edges") val columnEdges: List<ColumnEdge> = emptyList(),
    val selected: Selected? = null,
    /**
     * Optional non-fatal warning surfaced to the toolbar (e.g. "no Python
     * interpreter with sqlglot available — column lineage disabled").
     * Cleared on the next successful payload.
     */
    val warning: String? = null,
    /**
     * False while the sidecar is still streaming column-lineage edges
     * for [selected]; flips to true on the final payload (success or
     * failure). React uses this to keep the "computing column lineage…"
     * hint visible while edges trickle in, and to clear it once the
     * stream terminates.
     *
     * Defaults to true so non-streaming publish paths (full-payload
     * refreshes, hop changes, etc.) don't accidentally signal "still
     * computing" and leave the UI in a never-clears state.
     */
    @SerialName("column_lineage_done") val columnLineageDone: Boolean = true,
)

internal val LineageJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}
