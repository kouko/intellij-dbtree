package dev.kouko.intellijdbtree.lineage

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire-format DTO for the Python sidecar's `--full-walk` mode.
 *
 * Before this rewrite, the plugin recursed Kotlin-side and made one
 * subprocess call per (uid, col) — typically 30-90 calls per click,
 * each paying Python cold-start (~150ms). That recursion now lives in
 * the Python walker (`dbtree_lineage.walker.walk_full_lineage`); the
 * plugin makes a single subprocess call per click.
 *
 * Keep [ColumnEdge] and [FullWalkResult] in sync with the Python
 * sidecar's JSON output (see python-sidecar/src/dbtree_lineage/walker.py).
 */

@Serializable
internal data class FullWalkResult(
    val edges: List<FullWalkEdge> = emptyList(),
    /** Soft hint to surface to the user (e.g. "Run `dbt compile` first"). */
    val notice: String? = null,
)

@Serializable
internal data class FullWalkEdge(
    @SerialName("source_unique_id") val sourceUniqueId: String,
    @SerialName("source_column") val sourceColumn: String,
    @SerialName("target_unique_id") val targetUniqueId: String,
    @SerialName("target_column") val targetColumn: String,
    val expression: String? = null,
)

@Serializable
internal data class ListColumnsResult(
    val columns: List<String> = emptyList(),
    val error: String? = null,
)

internal fun FullWalkEdge.toColumnEdge(): ColumnEdge = ColumnEdge(
    sourceUniqueId = sourceUniqueId,
    sourceColumn = sourceColumn,
    targetUniqueId = targetUniqueId,
    targetColumn = targetColumn,
    expression = expression,
)
