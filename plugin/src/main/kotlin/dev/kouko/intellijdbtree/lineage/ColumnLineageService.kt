package dev.kouko.intellijdbtree.lineage

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import dev.kouko.intellijdbtree.settings.DbtreeSettingsService
import dev.kouko.intellijdbtree.sidecar.SidecarExtractor
import kotlinx.serialization.json.Json

/**
 * IDE-side wrapper that runs the bundled Python sidecar
 * (Phase C `dbtree_lineage` CLI) with the user-configured Python
 * interpreter (which must have `sqlglot` installed).
 *
 * The actual stitching logic lives in [ColumnLineageWalker]; this service
 * is just the I/O boundary — it owns the subprocess invocation and the
 * service-level dependencies (settings, sidecar extraction).
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

        log.info("ColumnLineageService: traced $modelUid.$column -> ${edges.size} edges (up + down)")
        return edges
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
