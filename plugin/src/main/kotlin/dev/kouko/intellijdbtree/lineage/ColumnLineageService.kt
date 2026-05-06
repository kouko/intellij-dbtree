package dev.kouko.intellijdbtree.lineage

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import dev.kouko.intellijdbtree.settings.DbtreeSettingsService
import dev.kouko.intellijdbtree.sidecar.SidecarExtractor
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Computes column-level lineage by spawning the bundled Python sidecar
 * (Phase C `dbtree_lineage` CLI) with the user-configured Python
 * interpreter (which must have `sqlglot` installed).
 *
 * sqlglot only sees one model's compiled SQL at a time and stops at the
 * first table reference. To get cross-model lineage (column X in model A
 * comes from column Y in upstream model B comes from column Z in source S),
 * we recursively call the sidecar once per (model, column) hop and stitch
 * the resulting edges in Kotlin.
 *
 * Phase A2 limits:
 *  - One subprocess per call (no long-lived RPC server). Each call ~1s.
 *  - No caching. Re-runs the whole chain on every column click.
 */
@Service(Service.Level.PROJECT)
class ColumnLineageService(private val project: Project) {

    private val log = Logger.getInstance(ColumnLineageService::class.java)
    private val sidecarJson = Json { ignoreUnknownKeys = true }

    /**
     * Trace [column] of model [modelUid] both upstream and downstream and
     * return every column-to-column edge encountered. Empty if Python
     * interpreter is not configured or the sidecar can't run.
     */
    fun computeForColumn(modelUid: String, column: String, manifest: ParsedManifest): List<ColumnEdge> {
        val python = DbtreeSettingsService.getInstance().state.pythonInterpreterPath.trim()
        if (python.isBlank()) {
            log.info("ColumnLineageService: no Python interpreter configured; skipping")
            return emptyList()
        }
        val sidecarDir = try {
            SidecarExtractor.ensureExtracted()
        } catch (e: Exception) {
            log.warn("ColumnLineageService: failed to extract sidecar", e)
            return emptyList()
        }

        val edges = mutableListOf<ColumnEdge>()

        // Upstream
        val upVisited = mutableSetOf<Pair<String, String>>()
        traceUpstream(
            modelUid = modelUid,
            column = column,
            python = python,
            pythonPath = sidecarDir.toString(),
            manifest = manifest,
            visited = upVisited,
            edges = edges,
        )

        // Downstream
        val downVisited = mutableSetOf<Pair<String, String>>()
        traceDownstream(
            modelUid = modelUid,
            column = column,
            python = python,
            pythonPath = sidecarDir.toString(),
            manifest = manifest,
            visited = downVisited,
            edges = edges,
        )

        log.info("ColumnLineageService: traced $modelUid.$column -> ${edges.size} edges (up + down)")
        return edges
    }

    private fun traceUpstream(
        modelUid: String,
        column: String,
        python: String,
        pythonPath: String,
        manifest: ParsedManifest,
        visited: MutableSet<Pair<String, String>>,
        edges: MutableList<ColumnEdge>,
    ) {
        if (!visited.add(modelUid to column)) return

        val result = runSidecarSingle(
            python = python,
            pythonPath = pythonPath,
            projectDir = manifest.dbtProjectDir.toString(),
            modelUid = modelUid,
            column = column,
        ) ?: return

        val rootExpression = result.lineage.expression?.takeIf { it.isNotBlank() }
        for (sc in result.sourceColumns) {
            val resolved = resolveSource(sc, manifest) ?: continue
            val (sourceUid, sourceCol) = resolved
            edges += ColumnEdge(
                sourceUniqueId = sourceUid,
                sourceColumn = sourceCol,
                targetUniqueId = modelUid,
                targetColumn = column,
                expression = rootExpression,
            )
            // Don't recurse into sources — they don't have compiled SQL,
            // so the sidecar would fail with "No model named ...".
            if (sourceUid.startsWith("source.")) continue
            traceUpstream(
                modelUid = sourceUid,
                column = sourceCol,
                python = python,
                pythonPath = pythonPath,
                manifest = manifest,
                visited = visited,
                edges = edges,
            )
        }
    }

    /**
     * Walk every direct downstream model of [modelUid] and find columns that
     * cite (target_model, target_column) as one of their leaves. One Python
     * invocation per direct child (using --all-columns); recursion goes
     * deeper through Kotlin.
     */
    private fun traceDownstream(
        modelUid: String,
        column: String,
        python: String,
        pythonPath: String,
        manifest: ParsedManifest,
        visited: MutableSet<Pair<String, String>>,
        edges: MutableList<ColumnEdge>,
    ) {
        if (!visited.add(modelUid to column)) return

        val targetName = manifest.modelName(modelUid) ?: return
        for (childUid in manifest.directChildren(modelUid)) {
            val all = runSidecarAllColumns(
                python = python,
                pythonPath = pythonPath,
                projectDir = manifest.dbtProjectDir.toString(),
                modelUid = childUid,
            ) ?: continue
            for (entry in all.columns) {
                val srcs = entry.sourceColumns ?: continue
                val matchesTarget = srcs.any { sc ->
                    val table = extractTableName(sc.table) ?: return@any false
                    val col = sc.column.substringAfterLast('.').trim('"', '`', '\'')
                    table == targetName && col == column
                }
                if (!matchesTarget) continue
                val rootExpression = entry.lineage?.expression?.takeIf { it.isNotBlank() }
                edges += ColumnEdge(
                    sourceUniqueId = modelUid,
                    sourceColumn = column,
                    targetUniqueId = childUid,
                    targetColumn = entry.column,
                    expression = rootExpression,
                )
                traceDownstream(
                    modelUid = childUid,
                    column = entry.column,
                    python = python,
                    pythonPath = pythonPath,
                    manifest = manifest,
                    visited = visited,
                    edges = edges,
                )
            }
        }
    }

    private fun runSidecarSingle(
        python: String,
        pythonPath: String,
        projectDir: String,
        modelUid: String,
        column: String,
    ): SidecarResult? {
        val cmd = sidecarCommand(python, pythonPath, projectDir, modelUid).apply {
            addParameters("--column", column)
        }
        return runSidecar(cmd, "$modelUid.$column", SidecarResult.serializer())
    }

    private fun runSidecarAllColumns(
        python: String,
        pythonPath: String,
        projectDir: String,
        modelUid: String,
    ): AllColumnsResult? {
        val cmd = sidecarCommand(python, pythonPath, projectDir, modelUid).apply {
            addParameter("--all-columns")
        }
        return runSidecar(cmd, "$modelUid (all columns)", AllColumnsResult.serializer())
    }

    private fun sidecarCommand(
        python: String,
        pythonPath: String,
        projectDir: String,
        modelUid: String,
    ): GeneralCommandLine {
        val cmd = GeneralCommandLine(
            python,
            "-m", "dbtree_lineage.cli",
            "--project-dir", projectDir,
            "--model", modelUid,
        )
        cmd.environment["PYTHONPATH"] = pythonPath
        cmd.charset = Charsets.UTF_8
        return cmd
    }

    private fun <T> runSidecar(
        cmd: GeneralCommandLine,
        label: String,
        deserializer: kotlinx.serialization.KSerializer<T>,
    ): T? {
        return try {
            val handler = CapturingProcessHandler(cmd)
            val out = handler.runProcess(SIDECAR_TIMEOUT_MS, true)
            if (out.exitCode != 0) {
                log.info("Sidecar failed for $label: exit=${out.exitCode} stderr=${out.stderr.take(400)}")
                return null
            }
            sidecarJson.decodeFromString(deserializer, out.stdout)
        } catch (e: Exception) {
            log.warn("Sidecar invocation crashed for $label", e)
            null
        }
    }

    /**
     * Match a sqlglot-reported source-column (e.g. table=`"jaffle_shop"."main"."stg_orders"
     * AS stg_orders`, column=`stg_orders.amount`) back to a dbt model
     * unique_id + bare column name.
     */
    private fun resolveSource(sc: SourceColumn, manifest: ParsedManifest): Pair<String, String>? {
        val table = extractTableName(sc.table) ?: return null
        val uid = manifest.findModelByName(table)
            ?: manifest.findSourceByName(table)
            ?: return null
        // sc.column is like "alias.col" or just "col"
        val col = sc.column.substringAfterLast('.').trim('"', '`', '\'')
        return uid to col
    }

    private fun extractTableName(tableExpr: String): String? {
        // "schema.table AS alias" -> "schema.table" -> "table"
        val withoutAlias = tableExpr.substringBefore(" AS ").trim()
        if (withoutAlias.isEmpty()) return null
        return withoutAlias.split('.').lastOrNull()?.trim('"', '`', '\'')?.takeIf { it.isNotEmpty() }
    }

    @Serializable
    private data class SidecarResult(
        val column: String,
        val lineage: SidecarNode,
        @SerialName("source_columns") val sourceColumns: List<SourceColumn>,
    )

    @Serializable
    private data class SidecarNode(
        val name: String,
        @SerialName("source_type") val sourceType: String,
        val expression: String? = null,
        val table: String? = null,
        val downstream: List<SidecarNode> = emptyList(),
    )

    @Serializable
    private data class SourceColumn(val table: String, val column: String)

    @Serializable
    private data class AllColumnsResult(
        val columns: List<ColumnEntry> = emptyList(),
    )

    @Serializable
    private data class ColumnEntry(
        val column: String,
        val lineage: SidecarNode? = null,
        @SerialName("source_columns") val sourceColumns: List<SourceColumn>? = null,
        val error: String? = null,
    )

    companion object {
        private const val SIDECAR_TIMEOUT_MS = 15_000
    }
}

private fun Project.columnLineageService(): ColumnLineageService = service()
