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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference

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

    /**
     * First user-actionable sidecar failure observed during the current
     * [computeForColumn] call. Cleared on entry, read on exit. Concurrent
     * column clicks are already serialized in practice by [LineageInfoService]'s
     * epoch counter (older calls drop their results before the publish), so
     * a single ref is enough — older calls' failures get overwritten but
     * those calls are about to be discarded anyway.
     */
    private val currentFailure = AtomicReference<String?>(null)

    sealed interface Result {
        data class Ok(val edges: List<ColumnEdge>) : Result
        data class Failed(val warning: String) : Result
    }

    /**
     * Terminal outcome of [computeForColumnStream]. Edges are delivered
     * via the `onEdge` callback rather than returned here — callers
     * accumulate / debounce / publish them as they prefer.
     */
    sealed interface StreamOutcome {
        /** Sidecar finished cleanly. [notice] is the optional soft hint
         *  emitted by the walker's `{"done": {"notice": ...}}` line. */
        data class Ok(val notice: String?) : StreamOutcome

        /** Sidecar didn't finish cleanly (timeout, non-zero exit,
         *  resolver / extraction failure). [warning] is user-facing. */
        data class Failed(val warning: String) : StreamOutcome
    }

    /** Internal: result of one reader-thread pass over sidecar stdout. */
    private data class StreamReadResult(val edgeCount: Int, val notice: String?)

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

        // Reset failure capture for this trace. The single sidecar call
        // below may write here if it crashes or returns a non-zero exit.
        currentFailure.set(null)

        // ONE subprocess per click, regardless of lineage depth/breadth.
        // The Python walker (`dbtree_lineage.walker.walk_full_lineage`)
        // does the recursion in-process — see python-sidecar/src/dbtree_lineage/walker.py.
        val cmd = sidecarCommand(python, pythonPath, projectDir, modelUid).apply {
            addParameters("--column", column, "--full-walk")
        }
        val result = runSidecar(cmd, "$modelUid.$column (full-walk)", FullWalkResult.serializer())

        if (result == null) {
            val failure = currentFailure.get()
            return if (failure != null) Result.Failed(failure) else Result.Ok(emptyList())
        }

        val edges = result.edges.map(FullWalkEdge::toColumnEdge)
        log.info(
            "ColumnLineageService: traced $modelUid.$column via $source ($python) " +
                "-> ${edges.size} edges (single sidecar call)",
        )
        // Surface walker-side notice (e.g. "manifest needs dbt compile")
        // as a Failed result so it banners. We only get here when the
        // sidecar exited cleanly, so the notice is the only signal the
        // user has that something's misconfigured.
        result.notice?.takeIf { it.isNotBlank() && edges.isEmpty() }?.let {
            return Result.Failed(it)
        }
        return Result.Ok(edges)
    }

    /**
     * Streaming variant of [computeForColumn]. The sidecar runs with
     * `--full-walk --stream`, emitting one NDJSON line per discovered
     * edge; this method reads stdout line-by-line and invokes [onEdge]
     * synchronously as each edge arrives, then returns the final
     * outcome when the sidecar emits its `{"done": ...}` line.
     *
     * Caller is expected to publish progressive UI updates from inside
     * [onEdge] (debounced — every edge would publish-storm the React
     * side). The sidecar's terminal notice (e.g. "Run dbt compile
     * first") is surfaced via [StreamOutcome.Ok.notice] for callers to
     * banner; non-fatal failures (timeout, crash) become
     * [StreamOutcome.Failed].
     *
     * Reader thread + future-with-timeout is needed because Java's
     * [java.io.BufferedReader.readLine] blocks indefinitely; cancelling
     * the future + force-destroying the process is the only way to
     * unblock when the sidecar hangs past the user-configured timeout.
     */
    fun computeForColumnStream(
        modelUid: String,
        column: String,
        manifest: ParsedManifest,
        onEdge: (ColumnEdge) -> Unit,
    ): StreamOutcome {
        val resolution = PythonInterpreterResolver.resolve(project, manifest.dbtProjectDir)
        val (python, source) = when (resolution) {
            is PythonInterpreterResolver.Resolution.Ok ->
                resolution.pythonPath to resolution.source
            is PythonInterpreterResolver.Resolution.None -> {
                log.info("ColumnLineageService.stream: ${resolution.reason}")
                return StreamOutcome.Failed(resolution.reason)
            }
        }
        val sidecarDir = try {
            SidecarExtractor.ensureExtracted()
        } catch (e: Exception) {
            log.warn("ColumnLineageService.stream: failed to extract sidecar", e)
            return StreamOutcome.Failed(
                "Failed to extract bundled Python sidecar: ${e.message}",
            )
        }

        val cmd = sidecarCommand(
            python,
            sidecarDir.toString(),
            manifest.dbtProjectDir.toString(),
            modelUid,
        ).apply { addParameters("--column", column, "--full-walk", "--stream") }

        val timeoutMs = DbtreeSettingsService.getInstance().sidecarTimeoutMillis()
        val label = "$modelUid.$column (stream)"

        val process = try {
            cmd.createProcess()
        } catch (e: Exception) {
            log.warn("ColumnLineageService.stream: failed to spawn sidecar for $label", e)
            return StreamOutcome.Failed("Sidecar failed to start: ${e.message}")
        }

        val executor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "dbtree-sidecar-stream").apply { isDaemon = true }
        }
        try {
            val future = executor.submit<StreamReadResult> {
                readSidecarStream(process, onEdge)
            }
            val outcome = try {
                future.get(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            } catch (e: TimeoutException) {
                process.destroyForcibly()
                future.cancel(true)
                val secs = timeoutMs / 1000
                return StreamOutcome.Failed(
                    "Sidecar timed out after ${secs}s while computing $label. " +
                        "Raise \"Sidecar timeout\" in Settings → Tools → dbtree " +
                        "if your project's column chains are deep, or see idea.log for partial output.",
                )
            } catch (e: ExecutionException) {
                process.destroyForcibly()
                val cause = e.cause ?: e
                log.warn("ColumnLineageService.stream: reader thread crashed for $label", cause)
                return StreamOutcome.Failed("Sidecar reader crashed: ${cause.message}")
            }

            // Drain process exit code; stdout reader hit EOF, so the
            // sidecar has already finished or is about to.
            val exitCode = if (process.waitFor(2, TimeUnit.SECONDS)) {
                process.exitValue()
            } else {
                process.destroyForcibly()
                log.warn("ColumnLineageService.stream: $label didn't exit after EOF; killed")
                -1
            }

            if (exitCode != 0) {
                val stderr = process.errorStream.bufferedReader(Charsets.UTF_8).readText().trim()
                log.info(
                    "ColumnLineageService.stream: $label exited $exitCode " +
                        "(via $source / $python). stderr: $stderr",
                )
                return StreamOutcome.Failed(
                    if (stderr.isNotBlank()) "Sidecar exited with code $exitCode: $stderr"
                    else "Sidecar exited with code $exitCode while computing $label.",
                )
            }

            log.info(
                "ColumnLineageService.stream: $label via $source ($python) " +
                    "-> streamed ${outcome.edgeCount} edges, notice=${outcome.notice != null}",
            )
            return StreamOutcome.Ok(outcome.notice)
        } finally {
            executor.shutdownNow()
        }
    }

    private fun readSidecarStream(
        process: Process,
        onEdge: (ColumnEdge) -> Unit,
    ): StreamReadResult {
        var notice: String? = null
        var edgeCount = 0
        process.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
            for (rawLine in lines) {
                val line = rawLine.trim()
                if (line.isEmpty()) continue
                val obj = try {
                    sidecarJson.parseToJsonElement(line).jsonObject
                } catch (_: Exception) {
                    // Tolerate junk lines (e.g. uv warning lines on stdout
                    // when the user's interpreter happens to print there).
                    continue
                }
                when {
                    "edge" in obj -> {
                        val edge = try {
                            sidecarJson.decodeFromJsonElement(
                                FullWalkEdge.serializer(),
                                obj["edge"]!!,
                            )
                        } catch (e: Exception) {
                            log.warn("dbtree stream: malformed edge line: $line", e)
                            continue
                        }
                        onEdge(edge.toColumnEdge())
                        edgeCount++
                    }
                    "done" in obj -> {
                        notice = obj["done"]?.jsonObject?.get("notice")?.let { v ->
                            when (v) {
                                is JsonNull -> null
                                is JsonPrimitive -> v.content.takeIf { it.isNotBlank() }
                                else -> null
                            }
                        }
                    }
                    // "start" / unknown envelopes — ignore.
                }
            }
        }
        return StreamReadResult(edgeCount = edgeCount, notice = notice)
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
    fun listColumnsViaSidecar(modelUid: String, manifest: ParsedManifest): List<String>? =
        columnListCache.getOrCompute(modelUid) {
            val resolution = PythonInterpreterResolver.resolve(project, manifest.dbtProjectDir)
            val python = (resolution as? PythonInterpreterResolver.Resolution.Ok)?.pythonPath
                ?: run {
                    log.info(
                        "ColumnLineageService.listColumnsViaSidecar: " +
                            "${(resolution as PythonInterpreterResolver.Resolution.None).reason}",
                    )
                    return@getOrCompute null
                }
            val sidecarDir = try {
                SidecarExtractor.ensureExtracted()
            } catch (e: Exception) {
                log.warn("ColumnLineageService.listColumnsViaSidecar: failed to extract sidecar", e)
                return@getOrCompute null
            }

            val cmd = sidecarCommand(
                python,
                sidecarDir.toString(),
                manifest.dbtProjectDir.toString(),
                modelUid,
            ).apply { addParameter("--list-columns") }
            val result = runSidecar(cmd, "$modelUid (list-columns)", ListColumnsResult.serializer())
                ?: return@getOrCompute null

            val cols = parseColumnList(result.columns)
            log.info("ColumnLineageService.listColumnsViaSidecar: $modelUid -> ${cols.size} columns")
            cols
        }

    /** Drop cached column lists (call on manifest reload / refresh-from-disk). */
    fun invalidateColumnListCache() {
        columnListCache.clear()
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
        val timeoutSeconds = DbtreeSettingsService.getInstance().state.sidecarTimeoutSeconds
            .let(DbtreeSettingsService::clampTimeoutSeconds)
        val timeoutMs = timeoutSeconds * 1000

        return try {
            val out = CapturingProcessHandler(cmd).runProcess(timeoutMs, true)
            when (
                val outcome = processSidecarOutput(
                    isTimeout = out.isTimeout,
                    exitCode = out.exitCode,
                    stderr = out.stderr,
                    stdout = out.stdout,
                    label = label,
                    timeoutSeconds = timeoutSeconds,
                    deserializer = deserializer,
                    json = sidecarJson,
                )
            ) {
                is SidecarOutcome.Ok -> outcome.value
                is SidecarOutcome.Failed -> {
                    log.info("Sidecar failed for $label: ${outcome.message}")
                    currentFailure.compareAndSet(null, outcome.message)
                    null
                }
            }
        } catch (e: Exception) {
            val msg = "Sidecar invocation crashed for $label: ${e.message ?: e::class.simpleName}"
            log.warn(msg, e)
            currentFailure.compareAndSet(null, msg)
            null
        }
    }
}

/**
 * Outcome of a single sidecar invocation, decoupled from IntelliJ's
 * subprocess types. Pure data, used as the seam between
 * [ColumnLineageService.runSidecar]'s I/O half (subprocess + logging +
 * `currentFailure` mutation) and the post-processing half
 * ([processSidecarOutput]).
 */
internal sealed interface SidecarOutcome<out T> {
    data class Ok<T>(val value: T) : SidecarOutcome<T>
    data class Failed(val message: String) : SidecarOutcome<Nothing>
}

/**
 * Pure post-processor for one sidecar invocation. Takes the
 * [com.intellij.execution.process.ProcessOutput] fields as primitives so
 * tests don't need IntelliJ runtime classes — feed in fake stdout/stderr
 * and exercise the failure-classification + JSON-deserialization paths
 * in isolation.
 *
 * Failure classification (timeout / non-zero exit / blank-stderr fallback)
 * is delegated to [describeSidecarFailure]. JSON parse errors are caught
 * here and converted to a `Failed` outcome with a label-bearing message
 * so the caller doesn't have to thread the label through a separate catch.
 */
internal fun <T> processSidecarOutput(
    isTimeout: Boolean,
    exitCode: Int,
    stderr: String,
    stdout: String,
    label: String,
    timeoutSeconds: Int,
    deserializer: kotlinx.serialization.KSerializer<T>,
    json: Json,
): SidecarOutcome<T> {
    describeSidecarFailure(isTimeout, exitCode, stderr, label, timeoutSeconds)?.let {
        return SidecarOutcome.Failed(it)
    }
    return try {
        SidecarOutcome.Ok(json.decodeFromString(deserializer, stdout))
    } catch (e: kotlinx.serialization.SerializationException) {
        SidecarOutcome.Failed(
            "Sidecar returned invalid JSON for $label: " +
                (e.message?.take(200) ?: e::class.simpleName),
        )
    }
}

/**
 * Drop sqlglot's `["*"]` placeholder from a column-list response.
 *
 * sqlglot emits a literal `"*"` when SELECT * can't be expanded (no source
 * schema). Rendering that as a column name confuses users — treat it as
 * "no columns" instead. Pure function, so the rule is locked by tests.
 */
internal fun parseColumnList(raw: List<String>): List<String> = raw.filter { it != "*" }

/**
 * Cache get-or-compute with non-poisoning null semantics: if [compute]
 * returns null, the entry is NOT cached and the next call retries.
 *
 * `ConcurrentHashMap.computeIfAbsent` has the right semantics but Kotlin's
 * type-system signature insists on a non-null return for the lambda; this
 * extension just wraps the same behavior with a nullable result, which is
 * what every callsite in this file actually wants.
 */
internal fun <K, V : Any> ConcurrentHashMap<K, V>.getOrCompute(
    key: K,
    compute: () -> V?,
): V? {
    this[key]?.let { return it }
    val value = compute() ?: return null
    // putIfAbsent returns the existing value if another thread won the race;
    // either way the caller gets the same logical answer.
    return putIfAbsent(key, value) ?: value
}

/**
 * Classify a sidecar invocation outcome into a user-actionable warning
 * string, or null on success. Pure function — takes the
 * [com.intellij.execution.process.ProcessOutput] fields as primitives so
 * unit tests don't need IntelliJ runtime classes.
 *
 * Wording prioritizes "what should the user do?":
 *  - Timeout → tell them where to raise the limit (Settings → Tools → dbtree)
 *  - Non-zero exit → quote the first chunk of stderr so the issue is visible
 *    in the toolbar without forcing them to open idea.log
 */
internal fun describeSidecarFailure(
    isTimeout: Boolean,
    exitCode: Int,
    stderr: String,
    label: String,
    timeoutSeconds: Int,
): String? = when {
    isTimeout ->
        "Sidecar timed out after ${timeoutSeconds}s while computing $label. " +
            "Raise “Sidecar timeout” in Settings → Tools → dbtree if your " +
            "project's column chains are deep, or see idea.log for partial output."
    exitCode != 0 -> {
        val tail = stderr.trim().takeLast(200).ifBlank { "(no stderr)" }
        "Sidecar exited with code $exitCode while computing $label. " +
            "stderr: $tail. See idea.log for full output."
    }
    else -> null
}

private fun Project.columnLineageService(): ColumnLineageService = service()
