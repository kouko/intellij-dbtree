package dev.kouko.intellijdbtree.lineage

import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests over the in-DAG vs outside-DAG decision that drives whether
 * editor focus changes trigger a heavy DAG rebuild or a lightweight
 * selection update. The branch-coverage matters because it's the
 * difference between "DAG re-layouts every time you click" (bad) and
 * "DAG stays put while you navigate" (correct dbt Power User UX).
 */
class LineageInfoPolicyTest {

    @Nested
    inner class DecideFocusEventTests {

        @Test
        fun `uid in published set returns SelectionOnly`() {
            val decision = decideFocusEvent("model.demo.orders", setOf("model.demo.orders", "model.demo.customers"))
            assertIs<FocusDecision.SelectionOnly>(decision)
            assertEquals("model.demo.orders", decision.uid)
        }

        @Test
        fun `uid not in published set returns Rebuild`() {
            val decision = decideFocusEvent(
                uid = "model.demo.new_mart",
                publishedUids = setOf("model.demo.orders", "model.demo.customers"),
            )
            assertIs<FocusDecision.Rebuild>(decision)
            assertEquals("model.demo.new_mart", decision.uid)
        }

        @Test
        fun `empty published set forces Rebuild for any uid`() {
            // First-launch / no DAG yet — every focus change rebuilds.
            val decision = decideFocusEvent("model.demo.orders", emptySet())
            assertIs<FocusDecision.Rebuild>(decision)
        }

        @Test
        fun `decision preserves the uid through into the result`() {
            val a = decideFocusEvent("a", emptySet())
            val b = decideFocusEvent("b", setOf("b"))
            assertEquals("a", a.uid)
            assertEquals("b", b.uid)
        }
    }

    @Nested
    inner class IsSupersededTests {

        @Test
        fun `same epoch is not superseded`() {
            // Task captured epoch=5 at dispatch; service still at 5 → publish.
            assertFalse(isSuperseded(myEpoch = 5L, current = LineageInfoService.State(epoch = 5L)))
        }

        @Test
        fun `older task epoch is superseded by newer service state`() {
            // Task captured epoch=3, but a fresher user intent bumped service
            // to epoch=4 while the task was doing I/O → drop.
            assertTrue(isSuperseded(myEpoch = 3L, current = LineageInfoService.State(epoch = 4L)))
        }

        @Test
        fun `task epoch ahead of state never happens but is treated as superseded`() {
            // Defensive: if some bug let a task think it's "newer" than the
            // service, still drop rather than publish stale-or-future data.
            // Strict equality is the contract.
            assertTrue(isSuperseded(myEpoch = 9L, current = LineageInfoService.State(epoch = 4L)))
        }

        @Test
        fun `non-epoch state fields do not affect the decision`() {
            // The point of epoch is to be the SOLE arbiter — other state
            // mutations (active uid, hops) shouldn't make a still-current
            // task think it's stale.
            val a = LineageInfoService.State(epoch = 7L, activeUid = null)
            val b = LineageInfoService.State(epoch = 7L, activeUid = "model.demo.orders", upHops = 1)
            assertFalse(isSuperseded(myEpoch = 7L, current = a))
            assertFalse(isSuperseded(myEpoch = 7L, current = b))
        }
    }

    @Nested
    inner class ManifestStatusWarningTests {

        @Test
        fun `Ok yields null — caller should publish a real payload`() {
            val ok = ManifestService.RefreshResult.Ok(
                ParsedManifestForTest.empty(),
            )
            assertNull(manifestStatusWarning(ok))
        }

        @Test
        fun `NoDbtProject names the marker file the user must add`() {
            val msg = manifestStatusWarning(ManifestService.RefreshResult.NoDbtProject)
            assertNotNull(msg)
            // Must mention dbt_project.yml so user knows what we're scanning for.
            assertTrue("dbt_project.yml" in msg, "must point at the discovery marker, was: $msg")
        }

        @Test
        fun `NoManifest names the path AND the dbt command that creates it`() {
            // The #1 first-time confusion: "I installed the plugin but see nothing."
            // The fix is almost always `dbt parse`. Spell that out.
            val msg = manifestStatusWarning(
                ManifestService.RefreshResult.NoManifest(Path.of("/repo/dbt/target/manifest.json")),
            )
            assertNotNull(msg)
            assertTrue("manifest.json" in msg)
            assertTrue("/repo/dbt/target/manifest.json" in msg, "must echo the path so user verifies which project")
            assertTrue("dbt parse" in msg, "must tell the user the exact command to run")
        }

        @Test
        fun `ParseError surfaces filename and points at idea_log for the cause`() {
            // We deliberately don't dump the full stack trace into the toolbar
            // banner — too noisy. idea.log already has the full Throwable from
            // ManifestService.refresh. The toolbar gives just enough to know
            // which file went bad and where to look for details.
            val cause = RuntimeException("Unexpected EOF at line 42")
            val msg = manifestStatusWarning(
                ManifestService.RefreshResult.ParseError(
                    Path.of("/repo/dbt/target/manifest.json"),
                    cause,
                ),
            )
            assertNotNull(msg)
            assertTrue("manifest.json" in msg)
            assertTrue("Unexpected EOF" in msg || cause::class.simpleName!! in msg)
            assertTrue("idea.log" in msg, "must direct user to idea.log for the full trace")
        }
    }

    @Nested
    inner class IsManifestStatusEventTests {

        @Test
        fun `manifest_json under target matches`() {
            assertTrue(isManifestStatusEvent("/repo/dbt/target/manifest.json"))
            // Monorepo: dbt project lives in a subfolder.
            assertTrue(isManifestStatusEvent("/repo/services/data/dbt/target/manifest.json"))
        }

        @Test
        fun `catalog_json under target matches`() {
            assertTrue(isManifestStatusEvent("/repo/dbt/target/catalog.json"))
        }

        @Test
        fun `manifest_json outside target does NOT match`() {
            // We only react to dbt's compile output, not arbitrary user files
            // that happen to be named manifest.json (e.g. a Webpack manifest).
            assertFalse(isManifestStatusEvent("/repo/dbt/manifest.json"))
            assertFalse(isManifestStatusEvent("/repo/frontend/dist/manifest.json"))
        }

        @Test
        fun `partial filename matches do NOT trip the predicate`() {
            // Backups, partial writes, etc. shouldn't trigger a refresh.
            assertFalse(isManifestStatusEvent("/repo/dbt/target/manifest.json.bak"))
            assertFalse(isManifestStatusEvent("/repo/dbt/target/manifest.json.tmp"))
            assertFalse(isManifestStatusEvent("/repo/dbt/target/old_manifest.json"))
        }

        @Test
        fun `other files in target do NOT match`() {
            // dbt produces dozens of files in target/ — we only want the two
            // that affect lineage rendering.
            assertFalse(isManifestStatusEvent("/repo/dbt/target/run_results.json"))
            assertFalse(isManifestStatusEvent("/repo/dbt/target/graph.gpickle"))
            assertFalse(isManifestStatusEvent("/repo/dbt/target/compiled/foo.sql"))
        }
    }

    private object ParsedManifestForTest {
        fun empty(): ParsedManifest {
            // Minimal valid ParsedManifest for the Ok branch test. The actual
            // contents don't matter — manifestStatusWarning(Ok) just returns null.
            val raw = com.google.gson.JsonParser.parseString(
                """{"nodes":{},"sources":{},"child_map":{},"parent_map":{}}""",
            ).asJsonObject
            return ParsedManifest(raw, null, Path.of("/dbt"))
        }
    }
}
