package dev.kouko.intellijdbtree.lineage

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import dev.kouko.intellijdbtree.sidecar.SidecarExtractor
import kotlinx.serialization.json.Json

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
