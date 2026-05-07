package dev.kouko.intellijdbtree.sidecar

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.extensions.PluginId
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
 * Versioned by the plugin's own version string (resolved at runtime from
 * [PluginManagerCore]). Each plugin upgrade lands in a fresh subfolder,
 * and stale subfolders from earlier plugin versions are cleaned up after
 * a successful extraction so the cache doesn't grow unbounded.
 */
object SidecarExtractor {

    private val log = Logger.getInstance(SidecarExtractor::class.java)

    private const val PLUGIN_ID = "dev.kouko.intellij-dbtree"

    /**
     * Fallback when the plugin descriptor isn't queryable (only happens in
     * unit tests / very early init paths). Picks a stable string so we still
     * extract somewhere predictable rather than throwing.
     */
    private const val FALLBACK_VERSION = "dev"

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
     * needing to bump the plugin version.
     *
     * After a successful extraction, removes any sibling version directories
     * left over from previous plugin upgrades.
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
        log.info("SidecarExtractor: extracted dbtree_lineage v${pluginVersion()} to $target")

        cleanupOldSidecarVersions(
            sidecarParent = target.parent ?: return target,
            currentVersion = pluginVersion(),
            onCleanup = { log.info("SidecarExtractor: removed stale sidecar dir $it") },
            onError = { dir, e -> log.warn("SidecarExtractor: failed to remove $dir", e) },
        )

        return target
    }

    private fun sidecarRoot(): Path =
        Path.of(PathManager.getSystemPath())
            .resolve("intellij-dbtree")
            .resolve("sidecar")
            .resolve(pluginVersion())

    private fun pluginVersion(): String =
        PluginManagerCore.getPlugin(PluginId.getId(PLUGIN_ID))?.version ?: FALLBACK_VERSION
}

/**
 * Delete every directory directly under [sidecarParent] whose name doesn't
 * match [currentVersion]. Best-effort: per-directory failures are reported
 * via [onError] so one stuck dir (locked file, permission denied) doesn't
 * abort the rest.
 *
 * Pure file-I/O — extracted as `internal` so unit tests can verify the
 * sweep without booting an IntelliJ test fixture.
 */
internal fun cleanupOldSidecarVersions(
    sidecarParent: Path,
    currentVersion: String,
    onCleanup: (Path) -> Unit = {},
    onError: (Path, Throwable) -> Unit = { _, _ -> },
) {
    if (!Files.isDirectory(sidecarParent)) return
    Files.list(sidecarParent).use { stream ->
        stream.forEach { dir ->
            if (!Files.isDirectory(dir)) return@forEach
            if (dir.fileName.toString() == currentVersion) return@forEach
            try {
                deleteDirectoryRecursively(dir)
                onCleanup(dir)
            } catch (e: Exception) {
                onError(dir, e)
            }
        }
    }
}

private fun deleteDirectoryRecursively(path: Path) {
    if (!Files.exists(path)) return
    Files.walk(path).use { stream ->
        // Reverse order so we delete files before their parent directories.
        stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }
}
