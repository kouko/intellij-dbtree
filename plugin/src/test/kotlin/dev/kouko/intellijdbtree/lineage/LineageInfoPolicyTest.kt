package dev.kouko.intellijdbtree.lineage

import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
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
}
