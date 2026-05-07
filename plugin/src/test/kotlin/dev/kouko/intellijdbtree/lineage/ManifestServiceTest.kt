package dev.kouko.intellijdbtree.lineage

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests over [loadManifestFromDisk] — the pure file-I/O half of
 * [ManifestService.refresh]. Covers each of the four [ManifestService.RefreshResult]
 * branches plus the catalog-parse-fail fallback (which is the only logic
 * path inside `refresh()` not already exercised by [ParsedManifestTest]).
 *
 * The IDE-coupled half (`DbtProjectDetector.findFirst`, `state` mutation,
 * Logger emission) is covered by manual / sandbox testing, matching the
 * `DbtProjectDetector.findAll` precedent.
 */
class ManifestServiceTest {

    @Test
    fun `null dbtProjectDir yields NoDbtProject`() {
        val result = loadManifestFromDisk(dbtProjectDir = null)
        assertIs<ManifestService.RefreshResult.NoDbtProject>(result)
    }

    @Test
    fun `existing project but no manifest_json yields NoManifest with full path`(@TempDir tmp: Path) {
        // No target/ at all.
        val result = loadManifestFromDisk(dbtProjectDir = tmp)
        val r = assertIs<ManifestService.RefreshResult.NoManifest>(result)
        assertEquals(tmp.resolve("target").resolve("manifest.json"), r.path)
    }

    @Test
    fun `target dir exists but manifest_json is missing yields NoManifest`(@TempDir tmp: Path) {
        Files.createDirectories(tmp.resolve("target"))
        val result = loadManifestFromDisk(dbtProjectDir = tmp)
        val r = assertIs<ManifestService.RefreshResult.NoManifest>(result)
        assertEquals(tmp.resolve("target").resolve("manifest.json"), r.path)
    }

    @Test
    fun `malformed manifest_json yields ParseError carrying path and cause`(@TempDir tmp: Path) {
        val targetDir = Files.createDirectories(tmp.resolve("target"))
        val manifestPath = targetDir.resolve("manifest.json")
        Files.writeString(manifestPath, "not valid json {{{")

        val result = loadManifestFromDisk(dbtProjectDir = tmp)
        val r = assertIs<ManifestService.RefreshResult.ParseError>(result)
        assertEquals(manifestPath, r.path)
        // cause comes from Gson; we just verify it's wired through, not the exact type.
        assertNotNull(r.cause)
    }

    @Test
    fun `valid manifest with no catalog yields Ok with hasCatalog=false`(@TempDir tmp: Path) {
        Files.createDirectories(tmp.resolve("target"))
        Files.writeString(tmp.resolve("target/manifest.json"), MINIMAL_MANIFEST)

        val result = loadManifestFromDisk(dbtProjectDir = tmp)
        val r = assertIs<ManifestService.RefreshResult.Ok>(result)
        assertEquals(tmp, r.manifest.dbtProjectDir)
        assertEquals(1, r.manifest.modelCount())
        assertTrue(!r.manifest.hasCatalog(), "no catalog.json on disk → hasCatalog() false")
    }

    @Test
    fun `valid manifest plus valid catalog yields Ok with hasCatalog=true`(@TempDir tmp: Path) {
        Files.createDirectories(tmp.resolve("target"))
        Files.writeString(tmp.resolve("target/manifest.json"), MINIMAL_MANIFEST)
        Files.writeString(tmp.resolve("target/catalog.json"), MINIMAL_CATALOG)

        val warnings = mutableListOf<String>()
        val result = loadManifestFromDisk(
            dbtProjectDir = tmp,
            onWarn = { msg, _ -> warnings.add(msg) },
        )
        val r = assertIs<ManifestService.RefreshResult.Ok>(result)
        assertTrue(r.manifest.hasCatalog())
        assertTrue(warnings.isEmpty(), "happy path must not emit warnings")
    }

    @Test
    fun `valid manifest plus malformed catalog still yields Ok and emits onWarn`(@TempDir tmp: Path) {
        // Contract: a broken catalog must NOT poison manifest loading. The
        // user gets the lineage graph from manifest.json; catalog enrichment
        // (warehouse-side column types) silently falls back to manifest data.
        // The warning is emitted so a developer reading idea.log can see why
        // their catalog data didn't show up.
        Files.createDirectories(tmp.resolve("target"))
        Files.writeString(tmp.resolve("target/manifest.json"), MINIMAL_MANIFEST)
        Files.writeString(tmp.resolve("target/catalog.json"), "garbage }")

        val warnings = mutableListOf<Pair<String, Throwable>>()
        val result = loadManifestFromDisk(
            dbtProjectDir = tmp,
            onWarn = { msg, e -> warnings.add(msg to e) },
        )
        val r = assertIs<ManifestService.RefreshResult.Ok>(result)
        assertTrue(!r.manifest.hasCatalog(), "broken catalog must be treated as absent")
        assertEquals(1, warnings.size, "broken catalog must emit exactly one warning")
        assertTrue(
            warnings[0].first.contains("catalog.json") && warnings[0].first.contains("ignoring"),
            "warning text must mention catalog.json + 'ignoring' so devs can grep idea.log",
        )
    }

    private companion object {
        // Minimal valid manifest: one model, no edges. Enough to exercise
        // the Ok branch and ParsedManifest construction.
        const val MINIMAL_MANIFEST = """
            {
              "nodes": {
                "model.demo.orders": {
                  "resource_type": "model",
                  "name": "orders",
                  "package_name": "demo",
                  "path": "marts/orders.sql",
                  "original_file_path": "models/marts/orders.sql"
                }
              },
              "sources": {},
              "child_map": { "model.demo.orders": [] },
              "parent_map": { "model.demo.orders": [] }
            }
        """

        // Minimal valid catalog. Empty nodes/sources is enough — we only
        // verify hasCatalog() flips, not column merge (covered by ParsedManifestTest).
        const val MINIMAL_CATALOG = """{ "nodes": {}, "sources": {} }"""
    }
}
