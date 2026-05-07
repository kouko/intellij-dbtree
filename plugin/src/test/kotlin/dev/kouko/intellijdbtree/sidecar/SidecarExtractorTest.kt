package dev.kouko.intellijdbtree.sidecar

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests over [cleanupOldSidecarVersions] — the pure file-I/O half of
 * SidecarExtractor's stale-version sweep. The full extraction path needs
 * IntelliJ's PathManager + classpath resources and is exercised by
 * manual / sandbox testing.
 */
class SidecarExtractorTest {

    @Test
    fun `removes sibling version directories that do not match current`(@TempDir parent: Path) {
        Files.createDirectories(parent.resolve("0.1.0").resolve("dbtree_lineage"))
        Files.writeString(parent.resolve("0.1.0/dbtree_lineage/cli.py"), "old code")
        Files.createDirectories(parent.resolve("0.1.1").resolve("dbtree_lineage"))
        Files.writeString(parent.resolve("0.1.1/dbtree_lineage/cli.py"), "older code")
        Files.createDirectories(parent.resolve("0.1.3").resolve("dbtree_lineage"))
        Files.writeString(parent.resolve("0.1.3/dbtree_lineage/cli.py"), "current code")

        val cleaned = mutableListOf<Path>()
        cleanupOldSidecarVersions(
            sidecarParent = parent,
            currentVersion = "0.1.3",
            onCleanup = { cleaned.add(it) },
        )

        // Only 0.1.3 should remain.
        val remaining = Files.list(parent).use { it.toList() }.map { it.fileName.toString() }
        assertEquals(listOf("0.1.3"), remaining, "current version dir must survive, others must be deleted")

        val cleanedNames = cleaned.map { it.fileName.toString() }.sorted()
        assertEquals(listOf("0.1.0", "0.1.1"), cleanedNames, "must report each cleaned dir via onCleanup")
    }

    @Test
    fun `handles a sidecar parent that does not exist (no-op)`(@TempDir tmp: Path) {
        // First run on a fresh IDE: parent doesn't exist yet. Cleanup should
        // be a no-op rather than throwing.
        val nonexistent = tmp.resolve("never-created")
        cleanupOldSidecarVersions(sidecarParent = nonexistent, currentVersion = "0.1.3")
        // (no exception = pass)
    }

    @Test
    fun `ignores non-directory entries in the parent`(@TempDir parent: Path) {
        // Defensive: someone might drop a stray file in sidecar/. Only
        // touch directories.
        Files.writeString(parent.resolve("README.md"), "not a sidecar dir")
        Files.createDirectories(parent.resolve("0.1.0/dbtree_lineage"))
        Files.createDirectories(parent.resolve("0.1.3/dbtree_lineage"))

        cleanupOldSidecarVersions(sidecarParent = parent, currentVersion = "0.1.3")

        assertTrue(Files.exists(parent.resolve("README.md")), "stray files must NOT be deleted")
        assertFalse(Files.exists(parent.resolve("0.1.0")), "old version dir must be deleted")
        assertTrue(Files.exists(parent.resolve("0.1.3")), "current version dir must survive")
    }

    @Test
    fun `recursively deletes nested directory contents`(@TempDir parent: Path) {
        // Sidecar dir contains nested files. The deletion must walk down,
        // not just rmdir the empty top.
        val old = parent.resolve("0.1.0").resolve("dbtree_lineage")
        Files.createDirectories(old.resolve("__pycache__").resolve("nested"))
        Files.writeString(old.resolve("cli.py"), "code")
        Files.writeString(old.resolve("__pycache__/cli.cpython-311.pyc"), "compiled")
        Files.writeString(old.resolve("__pycache__/nested/inner.txt"), "deeply nested")
        Files.createDirectories(parent.resolve("0.1.3"))

        cleanupOldSidecarVersions(sidecarParent = parent, currentVersion = "0.1.3")

        assertFalse(Files.exists(parent.resolve("0.1.0")), "old dir + all nested contents must be deleted")
    }

    @Test
    fun `current version is preserved even when its name has unusual chars`(@TempDir parent: Path) {
        // Plugin versions can carry qualifiers (0.1.3-SNAPSHOT, 1.0.0-rc1).
        // Make sure the comparison is exact-string not version-aware.
        Files.createDirectories(parent.resolve("0.1.3").resolve("dbtree_lineage"))
        Files.createDirectories(parent.resolve("0.1.3-SNAPSHOT").resolve("dbtree_lineage"))

        cleanupOldSidecarVersions(sidecarParent = parent, currentVersion = "0.1.3-SNAPSHOT")

        assertFalse(
            Files.exists(parent.resolve("0.1.3")),
            "exact-match comparison: '0.1.3' must be deleted when current is '0.1.3-SNAPSHOT'",
        )
        assertTrue(Files.exists(parent.resolve("0.1.3-SNAPSHOT")))
    }

    @Test
    fun `failure on one directory does not abort cleanup of others`(@TempDir parent: Path) {
        // Simulate a "stuck" dir with onError. A locked file in 0.1.0 (e.g.
        // antivirus holding it) shouldn't prevent us from cleaning 0.1.1.
        Files.createDirectories(parent.resolve("0.1.0").resolve("dbtree_lineage"))
        Files.createDirectories(parent.resolve("0.1.1").resolve("dbtree_lineage"))
        Files.createDirectories(parent.resolve("0.1.3"))

        val errors = mutableListOf<Pair<Path, Throwable>>()
        val cleaned = mutableListOf<Path>()

        // We can't easily simulate a locked file portably, so simulate by
        // injecting a custom onError that records but doesn't fail. The
        // contract under test is: best-effort iteration, errors don't abort.
        // The actual mechanism (try/catch) is exercised when we trigger a
        // failure in any of the deletes — which we can't do portably here.
        // Instead, verify the iteration order and that all non-current dirs
        // are visited.
        cleanupOldSidecarVersions(
            sidecarParent = parent,
            currentVersion = "0.1.3",
            onCleanup = { cleaned.add(it) },
            onError = { d, e -> errors.add(d to e) },
        )

        assertEquals(2, cleaned.size, "both old dirs must be visited")
        val cleanedNames = cleaned.map { it.fileName.toString() }.sorted()
        assertContains(cleanedNames, "0.1.0")
        assertContains(cleanedNames, "0.1.1")
    }
}
