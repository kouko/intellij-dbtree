package dev.kouko.intellijdbtree.lineage

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Pure column-lineage stitching logic, extracted from
 * [ColumnLineageService] so it can be tested without an IDE Project,
 * Python interpreter, or subprocess.
 *
 * The two walks ([traceUpstreamColumns] and [traceDownstreamColumns])
 * accept a sidecar-invocation lambda. Production callers wrap the
 * Python subprocess; tests pass a deterministic stub.
 *
 * **Source-prefix guard.** Both walks treat `source.*` uids as terminal:
 * dbt sources have no compiled SQL, and the Python sidecar bails with
 * "No model named source.X" if you ask it to trace one. This was a real
 * regression we fixed and want pinned by tests.
 */

// ---- Sidecar wire-format DTOs ------------------------------------------
// Mirror Python sidecar JSON output. `internal` so tests can build them
// without reflection. Keep in sync with `python-sidecar/src/dbtree_lineage`.

@Serializable
internal data class SidecarResult(
    val column: String,
    val lineage: SidecarNode,
    @SerialName("source_columns") val sourceColumns: List<SourceColumn>,
)

@Serializable
internal data class SidecarNode(
    val name: String,
    @SerialName("source_type") val sourceType: String,
    val expression: String? = null,
    val table: String? = null,
    val downstream: List<SidecarNode> = emptyList(),
)

@Serializable
internal data class SourceColumn(val table: String, val column: String)

@Serializable
internal data class AllColumnsResult(
    val columns: List<ColumnEntry> = emptyList(),
)

@Serializable
internal data class ColumnEntry(
    val column: String,
    val lineage: SidecarNode? = null,
    @SerialName("source_columns") val sourceColumns: List<SourceColumn>? = null,
    val error: String? = null,
)

@Serializable
internal data class ListColumnsResult(
    val columns: List<String> = emptyList(),
    val error: String? = null,
)

// ---- Helpers ----------------------------------------------------------

/**
 * Extract the bare table name from a sqlglot table expression.
 *
 * Examples:
 *   `"jaffle_shop"."main"."stg_orders" AS stg_orders` → `stg_orders`
 *   `"main"."orders"`                                  → `orders`
 *   `orders`                                           → `orders`
 *
 * Returns null on empty or whitespace-only input.
 */
internal fun extractTableName(tableExpr: String): String? {
    val withoutAlias = tableExpr.substringBefore(" AS ").trim()
    if (withoutAlias.isEmpty()) return null
    return withoutAlias
        .split('.')
        .lastOrNull()
        ?.trim('"', '`', '\'')
        ?.takeIf { it.isNotEmpty() }
}

/**
 * Map a sqlglot source-column report (table expression + alias-qualified
 * column name) back to a dbt unique_id + bare column name.
 *
 * Returns null when the table doesn't match any model or source name in
 * the manifest — usually means sqlglot resolved a CTE or subquery alias
 * that we can't trace beyond.
 */
internal fun resolveSource(
    sc: SourceColumn,
    manifest: ParsedManifest,
): Pair<String, String>? {
    val table = extractTableName(sc.table) ?: return null
    val uid = manifest.findModelByName(table)
        ?: manifest.findSourceByName(table)
        ?: return null
    // sc.column may be "alias.col" or just "col"
    val col = sc.column.substringAfterLast('.').trim('"', '`', '\'')
    return uid to col
}

// ---- Recursion engines ------------------------------------------------

/**
 * Walk upstream from (seedModelUid, seedColumn).
 *
 * For each (uid, col) reached: invoke [callSidecar], read its resolved
 * `source_columns`, emit one [ColumnEdge] per source, then recurse into
 * each non-source source. Per-hop visited-set protects against cycles.
 */
internal fun traceUpstreamColumns(
    seedModelUid: String,
    seedColumn: String,
    manifest: ParsedManifest,
    callSidecar: (String, String) -> SidecarResult?,
): List<ColumnEdge> {
    val edges = mutableListOf<ColumnEdge>()
    val visited = mutableSetOf<Pair<String, String>>()

    fun walk(uid: String, col: String) {
        if (!visited.add(uid to col)) return
        val result = callSidecar(uid, col) ?: return
        val rootExpression = result.lineage.expression?.takeIf { it.isNotBlank() }
        for (sc in result.sourceColumns) {
            val resolved = resolveSource(sc, manifest) ?: continue
            val (sourceUid, sourceCol) = resolved
            edges += ColumnEdge(
                sourceUniqueId = sourceUid,
                sourceColumn = sourceCol,
                targetUniqueId = uid,
                targetColumn = col,
                expression = rootExpression,
            )
            // Sources have no compiled SQL — recursion would crash the sidecar.
            if (sourceUid.startsWith("source.")) continue
            walk(sourceUid, sourceCol)
        }
    }

    walk(seedModelUid, seedColumn)
    return edges
}

/**
 * Walk downstream from (seedModelUid, seedColumn).
 *
 * Strategy differs from upstream: sqlglot only sees one model at a time
 * and can't enumerate "columns that consume this column", so we ask each
 * direct child model "what are the lineages of all your columns?" (one
 * sidecar call per child) and filter for columns that cite the seed.
 */
internal fun traceDownstreamColumns(
    seedModelUid: String,
    seedColumn: String,
    manifest: ParsedManifest,
    callSidecar: (String) -> AllColumnsResult?,
): List<ColumnEdge> {
    val edges = mutableListOf<ColumnEdge>()
    val visited = mutableSetOf<Pair<String, String>>()

    fun walk(uid: String, col: String) {
        if (!visited.add(uid to col)) return
        val targetName = manifest.modelName(uid) ?: return
        for (childUid in manifest.directChildren(uid)) {
            val all = callSidecar(childUid) ?: continue
            for (entry in all.columns) {
                val srcs = entry.sourceColumns ?: continue
                val matchesTarget = srcs.any { sc ->
                    val table = extractTableName(sc.table) ?: return@any false
                    val sourceCol = sc.column.substringAfterLast('.').trim('"', '`', '\'')
                    table == targetName && sourceCol == col
                }
                if (!matchesTarget) continue
                val rootExpression = entry.lineage?.expression?.takeIf { it.isNotBlank() }
                edges += ColumnEdge(
                    sourceUniqueId = uid,
                    sourceColumn = col,
                    targetUniqueId = childUid,
                    targetColumn = entry.column,
                    expression = rootExpression,
                )
                walk(childUid, entry.column)
            }
        }
    }

    walk(seedModelUid, seedColumn)
    return edges
}
