package dev.kouko.intellijdbtree.lineage

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.roots.ProjectRootManager
import dev.kouko.intellijdbtree.settings.DbtreeSettingsService
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * Determine which Python interpreter to invoke for column-level lineage.
 *
 * Resolution order (first hit wins):
 *
 *   1. **User-configured path** (`Settings → Tools → dbtree → Python interpreter`).
 *      Always wins when set, even if the path is broken — that surfaces the
 *      configuration error rather than silently picking something else.
 *
 *   2. **Project SDK** read via `ProjectRootManager.projectSdk` when the
 *      Python plugin is loaded (DataSpell / PyCharm / IntelliJ + Python).
 *      The SDK class lookup is wrapped so plugin-less IDEs don't crash.
 *
 *   3. **dbt project's `.venv`** at `<project>/.venv/bin/python` or the
 *      Windows equivalent — the convention `uv` and modern `python -m venv`
 *      use by default.
 *
 * For each candidate we run [validate] (a `python -c "import sqlglot"`
 * subprocess, ~100ms) before accepting it. The first candidate that BOTH
 * resolves to a path AND passes validation is returned via [Resolution.Ok];
 * otherwise the result describes which step failed and why, so the UI can
 * show a useful warning.
 *
 * The class-load probe in [findProjectSdkPath] is the standard pattern for
 * optional Python plugin integration: at runtime in an IDE without the
 * plugin, `PythonSdkUtil` simply isn't on the classpath, and we fall
 * through to step 3 instead of crashing.
 */
internal object PythonInterpreterResolver {

    private val log = Logger.getInstance(PythonInterpreterResolver::class.java)

    /**
     * Memoizes `python -c "import sqlglot"` results keyed by pythonPath.
     * The probe is ~3 seconds (JVM-to-Python cold start dominates) and
     * the v0.4.8 streaming batch sidecar runs it once per batch — when
     * the user expands several model cards in a row that adds up. The
     * interpreter path itself only changes when the user edits Settings
     * or the project SDK; both flow through [invalidateValidationCache].
     */
    private val validationCache = ConcurrentHashMap<String, Boolean>()

    /**
     * Clear the validation cache. Called by [LineageInfoService.refreshFromDisk]
     * so the ↻ toolbar button re-probes the interpreter alongside re-reading
     * the manifest — semantic match for "rebuild everything from scratch."
     */
    fun invalidateValidationCache() {
        validationCache.clear()
    }

    sealed interface Resolution {
        /** A working interpreter was found. */
        data class Ok(val pythonPath: String, val source: Source) : Resolution

        /** No candidate produced a working interpreter; UI should warn. */
        data class None(val reason: String) : Resolution
    }

    enum class Source { Manual, ProjectSdk, ProjectVenv }

    /**
     * Run the resolution chain. [validate] is injected so tests can stub
     * the sqlglot check.
     */
    fun resolve(
        project: Project,
        dbtProjectDir: Path?,
        validate: (String) -> Boolean = { defaultValidate(it) },
    ): Resolution {
        val candidates = mutableListOf<Pair<Source, String>>()

        DbtreeSettingsService.getInstance().state.pythonInterpreterPath
            .trim()
            .takeIf { it.isNotBlank() }
            ?.let { candidates += Source.Manual to it }

        findProjectSdkPath(project)?.let { candidates += Source.ProjectSdk to it }

        if (dbtProjectDir != null) {
            findVenvPath(dbtProjectDir)?.let { candidates += Source.ProjectVenv to it }
        }

        return runChain(candidates, validate)
    }

    /**
     * Pure resolution loop: take a prioritized list of (Source, path)
     * candidates and pick the first one that passes [validate]. Extracted
     * so tests can exercise the picking logic without an IDE Project.
     */
    internal fun runChain(
        candidates: List<Pair<Source, String>>,
        validate: (String) -> Boolean,
    ): Resolution {
        if (candidates.isEmpty()) {
            return Resolution.None(
                "No Python interpreter found. Configure one in Settings → Tools → dbtree, " +
                    "or set the project's Python interpreter in DataSpell.",
            )
        }

        for ((source, path) in candidates) {
            // computeIfAbsent: cache miss runs `validate` once and stores
            // the result; subsequent hits short-circuit the ~3s subprocess.
            // Both true AND false outcomes are cached — a broken path is
            // unlikely to fix itself mid-session, and re-probing on every
            // batch was the exact bottleneck this cache exists to solve.
            val ok = validationCache.computeIfAbsent(path) { validate(it) }
            if (ok) {
                log.info("PythonInterpreterResolver: using $source ($path)")
                return Resolution.Ok(path, source)
            } else {
                log.info("PythonInterpreterResolver: $source ($path) failed sqlglot check")
            }
        }

        // Suggest the install command for the highest-priority candidate.
        // Using the path-specific form (`<python> -m pip install sqlglot`)
        // pins the install to the environment we just tried, avoiding the
        // common "I ran `pip install sqlglot` and it still doesn't work"
        // confusion when the shell `pip` points at a different env.
        val (firstSource, firstPath) = candidates.first()
        return Resolution.None(
            "Python interpreter found at $firstPath ($firstSource), but it lacks the `sqlglot` package. " +
                "Install it with:\n  $firstPath -m pip install sqlglot\nAfter installing, click ↻ to refresh.",
        )
    }

    /**
     * Read the project's configured Python SDK via the Python plugin's API.
     * Returns null when the Python plugin isn't loaded (catches
     * NoClassDefFoundError that surfaces only at first invocation).
     */
    private fun findProjectSdkPath(project: Project): String? {
        return try {
            val sdk: Sdk? = ProjectRootManager.getInstance(project).projectSdk
            if (sdk != null && isPythonSdk(sdk)) sdk.homePath else null
        } catch (_: NoClassDefFoundError) {
            null
        } catch (e: Throwable) {
            log.warn("PythonInterpreterResolver: project SDK lookup failed", e)
            null
        }
    }

    /**
     * Probe for Python plugin's PythonSdkType class. The check works in two
     * phases: (a) Python plugin loaded → return type comparison; (b) Python
     * plugin not loaded → ClassNotFoundException at first reference, return
     * false. Both branches are safe for normal IDE projects whose SDK type
     * is JDK-based (Java/Kotlin), too — we just don't claim them.
     */
    private fun isPythonSdk(sdk: Sdk): Boolean {
        return try {
            val pythonSdkTypeClass = Class.forName("com.jetbrains.python.sdk.PythonSdkType")
            pythonSdkTypeClass.isInstance(sdk.sdkType)
        } catch (_: ClassNotFoundException) {
            // Python plugin not loaded; the SDK can't be Python-typed.
            false
        } catch (e: Throwable) {
            log.warn("PythonInterpreterResolver: PythonSdkType probe failed", e)
            false
        }
    }

    /**
     * Look for `<project>/.venv/bin/python` (Mac/Linux) or
     * `<project>\.venv\Scripts\python.exe` (Windows). Also tries the older
     * `venv/` (no leading dot) used by some `python -m venv` setups.
     *
     * [isExecutable] is injected so tests can stub the filesystem probe.
     */
    internal fun findVenvPath(
        dbtProjectDir: Path,
        isExecutable: (Path) -> Boolean = java.nio.file.Files::isExecutable,
    ): String? {
        val candidates = listOf(
            dbtProjectDir.resolve(".venv").resolve("bin").resolve("python"),
            dbtProjectDir.resolve(".venv").resolve("Scripts").resolve("python.exe"),
            dbtProjectDir.resolve("venv").resolve("bin").resolve("python"),
            dbtProjectDir.resolve("venv").resolve("Scripts").resolve("python.exe"),
        )
        return candidates.firstOrNull(isExecutable)?.toString()
    }

    /**
     * Default sqlglot probe. Spawns `pythonPath -c "import sqlglot"` and
     * checks exit code. Soft 5-second timeout; reused once per resolution
     * call (cache miss is OK — column-clicks happen at human cadence).
     */
    private fun defaultValidate(pythonPath: String): Boolean {
        return try {
            val pb = ProcessBuilder(pythonPath, "-c", "import sqlglot")
                .redirectErrorStream(true)
            val proc = pb.start()
            val finished = proc.waitFor(VALIDATION_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (!finished) {
                proc.destroyForcibly()
                return false
            }
            proc.exitValue() == 0
        } catch (e: Exception) {
            // Non-existent path, permission denied, etc.
            log.info("PythonInterpreterResolver: validate($pythonPath) crashed: ${e.message}")
            false
        }
    }

    private const val VALIDATION_TIMEOUT_MS = 5_000L
}
