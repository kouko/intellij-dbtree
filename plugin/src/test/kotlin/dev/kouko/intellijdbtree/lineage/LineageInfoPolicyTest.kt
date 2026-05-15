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

    /**
     * Pins the activeUid-vs-centerUid split that fixes two bugs at once:
     *  - Toolbar refresh must rebuild around what the user is currently
     *    looking at (uses activeUid, which drifts on selection)
     *  - Column trace must NOT churn topology when selection drifts
     *    across in-view clicks (uses centerUid, anchored)
     */
    @Nested
    inner class StateTransitionTests {

        @Test
        fun `publishFull moves activeUid and centerUid together`() {
            val before = LineageInfoService.State(
                activeUid = "old.focal",
                centerUid = "old.focal",
                publishedUids = setOf("old.focal", "old.up"),
            )
            val after = stateAfterPublishFull(
                before,
                newActiveUid = "new.focal",
                publishedUids = setOf("new.focal", "new.up", "new.down"),
            )
            assertEquals("new.focal", after.activeUid)
            assertEquals("new.focal", after.centerUid)
            assertEquals(setOf("new.focal", "new.up", "new.down"), after.publishedUids)
        }

        @Test
        fun `publishFull with null seed clears both activeUid and centerUid`() {
            val before = LineageInfoService.State(activeUid = "x", centerUid = "x")
            val after = stateAfterPublishFull(before, newActiveUid = null, publishedUids = emptySet())
            assertNull(after.activeUid)
            assertNull(after.centerUid)
        }

        @Test
        fun `selectionOnly drifts activeUid but anchors centerUid`() {
            val before = LineageInfoService.State(
                activeUid = "A",
                centerUid = "A",
                publishedUids = setOf("A", "B", "C"),
            )
            val after = stateAfterSelectionOnly(before, "B")
            assertEquals("B", after.activeUid)
            assertEquals("A", after.centerUid) // ← the load-bearing assertion
            assertEquals(setOf("A", "B", "C"), after.publishedUids)
        }

        @Test
        fun `selectionOnly preserves centerUid across multiple drifts`() {
            // Models the real-world bug: user clicks card B then card C;
            // centerUid must stay anchored to the original publishFull seed
            // so the next column trace still bases topology on the user's
            // explicit DAG view, not the latest in-view click.
            val initial = LineageInfoService.State(
                activeUid = "ORIGINAL_FOCAL",
                centerUid = "ORIGINAL_FOCAL",
                publishedUids = setOf("ORIGINAL_FOCAL", "B", "C"),
            )
            val afterClickB = stateAfterSelectionOnly(initial, "B")
            val afterClickC = stateAfterSelectionOnly(afterClickB, "C")
            assertEquals("C", afterClickC.activeUid)
            assertEquals("ORIGINAL_FOCAL", afterClickC.centerUid)
        }

        @Test
        fun `selectionOnly does not touch hops, epoch, or publishedUids`() {
            val before = LineageInfoService.State(
                activeUid = "A",
                centerUid = "A",
                upHops = 5,
                downHops = 7,
                epoch = 42L,
                publishedUids = setOf("A"),
            )
            val after = stateAfterSelectionOnly(before, "B")
            assertEquals(5, after.upHops)
            assertEquals(7, after.downHops)
            assertEquals(42L, after.epoch)
            assertEquals(setOf("A"), after.publishedUids)
        }
    }

    /**
     * Pins the column-request publish gate. Replacing epoch supersession
     * with a publishedUids check fixed the regression where any unrelated
     * payload-changing event (column click, hop change) would kill in-flight
     * column-list prefetches and leave cards stuck on "Parsing SQL…".
     */
    @Nested
    inner class ShouldPublishModelColumnsTests {

        @Test
        fun `publishes when model is in publishedUids`() {
            val state = LineageInfoService.State(publishedUids = setOf("model.foo", "model.bar"))
            assertTrue(shouldPublishModelColumns("model.foo", state))
        }

        @Test
        fun `drops when model is not in publishedUids`() {
            val state = LineageInfoService.State(publishedUids = setOf("model.foo"))
            assertFalse(shouldPublishModelColumns("model.bar", state))
        }

        @Test
        fun `drops when publishedUids is empty`() {
            val state = LineageInfoService.State(publishedUids = emptySet())
            assertFalse(shouldPublishModelColumns("model.foo", state))
        }

        @Test
        fun `does not consult epoch — epoch differences are irrelevant to the gate`() {
            // The original bug: column-prefetch tasks were keyed by epoch
            // and any unrelated bump would supersede them. The fix gates
            // strictly on publishedUids, so even a wildly different epoch
            // shouldn't change the decision.
            val state = LineageInfoService.State(
                publishedUids = setOf("model.foo"),
                epoch = 999L,
            )
            assertTrue(shouldPublishModelColumns("model.foo", state))
        }
    }
}
