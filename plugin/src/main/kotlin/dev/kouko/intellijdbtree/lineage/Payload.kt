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
    /** "staging" | "intermediate" | "marts" | "source" | null. */
    val layer: String? = null,
    val columns: List<ColumnSpec> = emptyList(),
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

@Serializable
data class LineagePayload(
    val models: List<DbtModel> = emptyList(),
    @SerialName("model_edges") val modelEdges: List<ModelEdge> = emptyList(),
    @SerialName("column_edges") val columnEdges: List<ColumnEdge> = emptyList(),
    val selected: Selected? = null,
)

internal val LineageJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}
