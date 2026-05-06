package dev.kouko.intellijdbtree.lineage

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Tests over the in-DAG vs outside-DAG decision that drives whether
 * editor focus changes trigger a heavy DAG rebuild or a lightweight
 * selection update. The branch-coverage matters because it's the
 * difference between "DAG re-layouts every time you click" (bad) and
 * "DAG stays put while you navigate" (correct dbt Power User UX).
 */
class LineageInfoPolicyTest {

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
