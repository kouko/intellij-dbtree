package dev.kouko.intellijdbtree.sidecar

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.Logger
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Extracts the bundled `dbtree_lineage` Python package from the plugin's
 * classpath into a stable on-disk location, so a Python interpreter can
 * import it via `PYTHONPATH`.
 *
 * Target directory follows JetBrains conventions: a per-IDE-install
 * subdirectory under [PathManager.getSystemPath] — that's the directory
 * meant for caches that can be regenerated without losing user data.
 *
 * Versioned: each bump of [SIDECAR_VERSION] writes to a fresh subfolder,
 * so plugin upgrades pick up new sidecar code without manual cleanup.
 */
object SidecarExtractor {

    private val log = Logger.getInstance(SidecarExtractor::class.java)

    /**
     * Bumped whenever any of the bundled Python files change — forces
     * re-extraction on the user's machine after a plugin upgrade.
     */
    const val SIDECAR_VERSION = "0.1.0"

    /** Files we expect to ship under `dbtree_lineage/` on the classpath. */
    private val BUNDLED_FILES = listOf(
        "__init__.py",
        "cli.py",
        "lineage.py",
        "manifest.py",
    )

    /**
     * Returns the directory containing `dbtree_lineage/`, suitable as
     * a `PYTHONPATH` entry. Extracts on first call; idempotent thereafter.
     *
     * In sandbox/dev mode (`-Didea.plugin.in.sandbox.mode=true`), always
     * re-extracts so iterative changes to the Python source land without
     * needing to bump [SIDECAR_VERSION].
     */
    @Synchronized
    fun ensureExtracted(): Path {
        val target = sidecarRoot()
        val sentinel = target.resolve("dbtree_lineage").resolve("__init__.py")
        val isSandbox = System.getProperty("idea.plugin.in.sandbox.mode") == "true"
        if (Files.exists(sentinel) && !isSandbox) return target

        Files.createDirectories(target.resolve("dbtree_lineage"))
        for (rel in BUNDLED_FILES) {
            val classpathPath = "dbtree_lineage/$rel"
            val stream = javaClass.classLoader.getResourceAsStream(classpathPath)
                ?: error("Bundled sidecar file missing on classpath: $classpathPath")
            stream.use { input ->
                Files.copy(
                    input,
                    target.resolve("dbtree_lineage").resolve(rel),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
        }
        log.info("SidecarExtractor: extracted dbtree_lineage v$SIDECAR_VERSION to $target")
        return target
    }

    private fun sidecarRoot(): Path =
        Path.of(PathManager.getSystemPath())
            .resolve("intellij-dbtree")
            .resolve("sidecar")
            .resolve(SIDECAR_VERSION)
}
