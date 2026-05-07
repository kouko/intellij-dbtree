package dev.kouko.intellijdbtree.lineage

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import dev.kouko.intellijdbtree.sidecar.SidecarExtractor
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

/**
 * IDE-side wrapper that runs the bundled Python sidecar
 * (Phase C `dbtree_lineage` CLI) with a Python interpreter resolved from
 * (in priority order) the user's setting, the project's IDE-configured
 * Python SDK, or the dbt project's `.venv`. See [PythonInterpreterResolver].
 *
 * The actual stitching logic lives in [ColumnLineageWalker]; this service
 * is just the I/O boundary — it owns the subprocess invocation and the
 * service-level dependencies (resolver, sidecar extraction).
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
     * Memo of sqlglot-derived column lists per model uid. Populated lazily
     * by [listColumnsViaSidecar] and consulted before spawning Python.
     * Cleared by [invalidateColumnListCache] on manifest reload so changes
     * to compiled SQL are picked up.
     */
    private val columnListCache = ConcurrentHashMap<String, List<String>>()

    sealed interface Result {
        data class Ok(val edges: List<ColumnEdge>) : Result
        data class Failed(val warning: String) : Result
    }

    /**
     * Trace [column] of model [modelUid] both upstream and downstream.
     * Returns [Result.Ok] with the edge list (possibly empty when sqlglot
     * traces nothing) or [Result.Failed] with a human-readable warning that
     * should bubble up to the toolbar.
     */
    fun computeForColumn(modelUid: String, column: String, manifest: ParsedManifest): Result {
        val resolution = PythonInterpreterResolver.resolve(project, manifest.dbtProjectDir)
        val (python, source) = when (resolution) {
            is PythonInterpreterResolver.Resolution.Ok ->
                resolution.pythonPath to resolution.source
            is PythonInterpreterResolver.Resolution.None -> {
                log.info("ColumnLineageService: ${resolution.reason}")
                return Result.Failed(resolution.reason)
            }
        }
        val sidecarDir = try {
            SidecarExtractor.ensureExtracted()
        } catch (e: Exception) {
            log.warn("ColumnLineageService: failed to extract sidecar", e)
            return Result.Failed("Failed to extract bundled Python sidecar: ${e.message}")
        }

        val pythonPath = sidecarDir.toString()
        val projectDir = manifest.dbtProjectDir.toString()

        val singleSidecar: (String, String) -> SidecarResult? = { uid, col ->
            runSidecarSingle(python, pythonPath, projectDir, uid, col)
        }
        val allColumnsSidecar: (String) -> AllColumnsResult? = { uid ->
            runSidecarAllColumns(python, pythonPath, projectDir, uid)
        }

        val edges = mutableListOf<ColumnEdge>()
        edges += traceUpstreamColumns(modelUid, column, manifest, singleSidecar)
        edges += traceDownstreamColumns(modelUid, column, manifest, allColumnsSidecar)

        log.info("ColumnLineageService: traced $modelUid.$column via $source ($python) -> ${edges.size} edges")
        return Result.Ok(edges)
    }

    /**
     * Parse the model's compiled SQL via sqlglot and return its output column
     * names. Used to populate cards for models whose schema.yml docs don't
     * list columns and whose project lacks a `target/catalog.json`.
     *
     * Caches by `modelUid` indefinitely (until [invalidateColumnListCache]).
     * Returns `null` when no usable Python interpreter can be resolved or the
     * sidecar fails — callers should treat this as "leave columns empty",
     * the warning banner will already explain how to fix it.
     */
    fun listColumnsViaSidecar(modelUid: String, manifest: ParsedManifest): List<String>? {
        columnListCache[modelUid]?.let { return it }

        val resolution = PythonInterpreterResolver.resolve(project, manifest.dbtProjectDir)
        val python = (resolution as? PythonInterpreterResolver.Resolution.Ok)?.pythonPath
            ?: run {
                log.info("ColumnLineageService.listColumnsViaSidecar: ${(resolution as PythonInterpreterResolver.Resolution.None).reason}")
                return null
            }
        val sidecarDir = try {
            SidecarExtractor.ensureExtracted()
        } catch (e: Exception) {
            log.warn("ColumnLineageService.listColumnsViaSidecar: failed to extract sidecar", e)
            return null
        }

        val cmd = sidecarCommand(python, sidecarDir.toString(), manifest.dbtProjectDir.toString(), modelUid).apply {
            addParameter("--list-columns")
        }
        val result = runSidecar(cmd, "$modelUid (list-columns)", ListColumnsResult.serializer())
            ?: return null

        // sqlglot returns ["*"] when SELECT * can't be expanded; treat that
        // as "no columns" rather than rendering a literal "*" entry.
        val cols = result.columns.filter { it != "*" }
        columnListCache[modelUid] = cols
        log.info("ColumnLineageService.listColumnsViaSidecar: $modelUid -> ${cols.size} columns")
        return cols
    }

    /** Drop cached column lists (call on manifest reload / refresh-from-disk). */
    fun invalidateColumnListCache() {
        columnListCache.clear()
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

    companion object {
        private const val SIDECAR_TIMEOUT_MS = 15_000
    }
}

private fun Project.columnLineageService(): ColumnLineageService = service()
