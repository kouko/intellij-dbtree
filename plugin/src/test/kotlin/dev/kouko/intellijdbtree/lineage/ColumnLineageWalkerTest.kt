package dev.kouko.intellijdbtree.lineage

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Smoke test for [FullWalkResult] deserialization. The recursion logic
 * itself now lives in the Python sidecar and is covered by
 * `python-sidecar/tests/test_walker.py`; this file just verifies the
 * Kotlin side parses the wire format correctly.
 */
class ColumnLineageWalkerTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `deserializes empty edges`() {
        val raw = """{"edges": []}"""
        val result = json.decodeFromString(FullWalkResult.serializer(), raw)
        assertEquals(0, result.edges.size)
    }

    @Test
    fun `deserializes a typical edge`() {
        val raw = """
            {
              "edges": [
                {
                  "source_unique_id": "model.demo.stg_orders",
                  "source_column": "id",
                  "target_unique_id": "model.demo.fct_orders",
                  "target_column": "id",
                  "expression": null
                }
              ]
            }
        """.trimIndent()

        val result = json.decodeFromString(FullWalkResult.serializer(), raw)
        assertEquals(1, result.edges.size)
        val e = result.edges[0]
        assertEquals("model.demo.stg_orders", e.sourceUniqueId)
        assertEquals("id", e.sourceColumn)
        assertEquals("model.demo.fct_orders", e.targetUniqueId)
        assertEquals("id", e.targetColumn)
        assertEquals(null, e.expression)
    }

    @Test
    fun `deserializes a derived expression`() {
        val raw = """
            {
              "edges": [
                {
                  "source_unique_id": "model.demo.stg_orders",
                  "source_column": "amount",
                  "target_unique_id": "model.demo.fct_orders",
                  "target_column": "amount_with_tax",
                  "expression": "amount * 1.05 AS amount_with_tax"
                }
              ]
            }
        """.trimIndent()

        val result = json.decodeFromString(FullWalkResult.serializer(), raw)
        assertEquals("amount * 1.05 AS amount_with_tax", result.edges[0].expression)
    }

    @Test
    fun `toColumnEdge maps fields straight through`() {
        val wire = FullWalkEdge(
            sourceUniqueId = "model.demo.a",
            sourceColumn = "x",
            targetUniqueId = "model.demo.b",
            targetColumn = "y",
            expression = "x + 1",
        )
        val edge = wire.toColumnEdge()
        assertEquals("model.demo.a", edge.sourceUniqueId)
        assertEquals("x", edge.sourceColumn)
        assertEquals("model.demo.b", edge.targetUniqueId)
        assertEquals("y", edge.targetColumn)
        assertEquals("x + 1", edge.expression)
    }
}
