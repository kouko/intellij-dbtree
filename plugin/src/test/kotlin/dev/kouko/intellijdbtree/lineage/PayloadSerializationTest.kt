package dev.kouko.intellijdbtree.lineage

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Verifies that the wire format pushed to the JCEF React UI uses the snake_case
 * field names the frontend expects. Field renames here are a contract break with
 * `frontend/src/types.ts` — these tests catch accidental drift.
 */
class PayloadSerializationTest {

    private val json = LineageJson

    @Test
    fun `DbtModel serializes camelCase fields as snake_case`() {
        val model = DbtModel(
            uniqueId = "model.demo.orders",
            name = "orders",
            packageName = "demo",
            layer = "marts",
            columns = listOf(ColumnSpec(name = "id", type = "bigint", description = "PK")),
        )
        val tree = json.parseToJsonElement(json.encodeToString(DbtModel.serializer(), model)).jsonObject

        assertEquals("model.demo.orders", tree["unique_id"]!!.jsonPrimitive.content)
        assertEquals("demo", tree["package_name"]!!.jsonPrimitive.content)
        assertFalse("uniqueId" in tree, "camelCase form must not appear")
        assertFalse("packageName" in tree, "camelCase form must not appear")
    }

    @Test
    fun `DbtModel folder field is serialized when set and elided when null`() {
        val withFolder = DbtModel(
            uniqueId = "model.demo.orders",
            name = "orders",
            packageName = "demo",
            layer = "marts",
            folder = "marts_msd",
        )
        val withoutFolder = DbtModel(
            uniqueId = "model.demo.orders",
            name = "orders",
            packageName = "demo",
        )
        val withTree = json.parseToJsonElement(json.encodeToString(DbtModel.serializer(), withFolder)).jsonObject
        val withoutTree = json.parseToJsonElement(json.encodeToString(DbtModel.serializer(), withoutFolder)).jsonObject

        assertEquals("marts_msd", withTree["folder"]!!.jsonPrimitive.content)
        assertFalse("folder" in withoutTree, "null folder is omitted (explicitNulls=false)")
    }

    @Test
    fun `ModelEdge serializes source_unique_id and target_unique_id`() {
        val edge = ModelEdge(sourceUniqueId = "a", targetUniqueId = "b")
        val tree = json.parseToJsonElement(json.encodeToString(ModelEdge.serializer(), edge)).jsonObject

        assertEquals("a", tree["source_unique_id"]!!.jsonPrimitive.content)
        assertEquals("b", tree["target_unique_id"]!!.jsonPrimitive.content)
        assertFalse("sourceUniqueId" in tree)
        assertFalse("targetUniqueId" in tree)
    }

    @Test
    fun `ColumnEdge serializes all four snake_case identifier fields`() {
        val edge = ColumnEdge(
            sourceUniqueId = "model.demo.stg_orders",
            sourceColumn = "order_id",
            targetUniqueId = "model.demo.orders",
            targetColumn = "id",
            expression = "order_id",
        )
        val tree = json.parseToJsonElement(json.encodeToString(ColumnEdge.serializer(), edge)).jsonObject

        assertEquals("model.demo.stg_orders", tree["source_unique_id"]!!.jsonPrimitive.content)
        assertEquals("order_id", tree["source_column"]!!.jsonPrimitive.content)
        assertEquals("model.demo.orders", tree["target_unique_id"]!!.jsonPrimitive.content)
        assertEquals("id", tree["target_column"]!!.jsonPrimitive.content)
        assertEquals("order_id", tree["expression"]!!.jsonPrimitive.content)
    }

    @Test
    fun `Selected serializes unique_id snake_case`() {
        val selected = Selected(uniqueId = "model.demo.orders", column = "id")
        val tree = json.parseToJsonElement(json.encodeToString(Selected.serializer(), selected)).jsonObject

        assertEquals("model.demo.orders", tree["unique_id"]!!.jsonPrimitive.content)
        assertEquals("id", tree["column"]!!.jsonPrimitive.content)
        assertFalse("uniqueId" in tree)
    }

    @Test
    fun `LineagePayload serializes model_edges and column_edges`() {
        val payload = LineagePayload(
            models = listOf(DbtModel("uid1", "n", "demo")),
            modelEdges = listOf(ModelEdge("a", "b")),
            columnEdges = listOf(ColumnEdge("a", "x", "b", "y")),
            selected = Selected("uid1", null),
        )
        val tree = json.parseToJsonElement(json.encodeToString(LineagePayload.serializer(), payload)).jsonObject

        assertTrue("model_edges" in tree)
        assertTrue("column_edges" in tree)
        assertFalse("modelEdges" in tree)
        assertFalse("columnEdges" in tree)
    }

    @Test
    fun `null fields are omitted (explicitNulls=false)`() {
        val model = DbtModel(uniqueId = "uid", name = "n", packageName = "demo", layer = null)
        val tree = json.parseToJsonElement(json.encodeToString(DbtModel.serializer(), model)).jsonObject
        assertFalse("layer" in tree, "null layer should be elided, not serialized as null")
    }

    @Test
    fun `empty default lists are still serialized (encodeDefaults=true)`() {
        val payload = LineagePayload()
        val tree = json.parseToJsonElement(json.encodeToString(LineagePayload.serializer(), payload)).jsonObject

        // Frontend code reads model_edges.length etc., so the keys must exist
        assertTrue("models" in tree)
        assertTrue("model_edges" in tree)
        assertTrue("column_edges" in tree)
        assertEquals(0, tree["models"]!!.jsonArray.size)
        assertEquals(0, tree["model_edges"]!!.jsonArray.size)
        assertEquals(0, tree["column_edges"]!!.jsonArray.size)
    }

    @Test
    fun `unknown fields on input do not break decoding (ignoreUnknownKeys=true)`() {
        val raw = """
            {
              "unique_id": "model.demo.orders",
              "name": "orders",
              "package_name": "demo",
              "future_field_we_dont_know_about": 42
            }
        """.trimIndent()

        val model = json.decodeFromString(DbtModel.serializer(), raw)
        assertEquals("model.demo.orders", model.uniqueId)
    }

    @Test
    fun `column_lineage_done serializes as snake_case and defaults true`() {
        // Default: streaming flag is true so non-streaming publishers
        // (full payloads, hop changes, etc.) don't trigger the React
        // "computing…" hint by accident.
        val defaultPayload = LineagePayload()
        val defaultTree = json.parseToJsonElement(json.encodeToString(LineagePayload.serializer(), defaultPayload)).jsonObject
        assertTrue("column_lineage_done" in defaultTree)
        assertTrue(defaultTree["column_lineage_done"]!!.jsonPrimitive.boolean)
        assertFalse("columnLineageDone" in defaultTree, "camelCase form must not appear")

        // Setting false flows through unchanged — used by the streaming
        // column-lineage publish path between start and final flush.
        val streamingPayload = LineagePayload(columnLineageDone = false)
        val streamingTree = json.parseToJsonElement(json.encodeToString(LineagePayload.serializer(), streamingPayload)).jsonObject
        assertFalse(streamingTree["column_lineage_done"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `DbtModel hidden_upstream and hidden_downstream serialize as snake_case`() {
        // Frontend reads `hidden_upstream` / `hidden_downstream` to render
        // the "+N" handle chip — camelCase here would silently disable the
        // chip on the React side without any compile-time signal.
        val model = DbtModel(
            uniqueId = "model.demo.orders",
            name = "orders",
            packageName = "demo",
            hiddenUpstream = 3,
            hiddenDownstream = 5,
        )
        val raw = json.encodeToString(DbtModel.serializer(), model)
        val tree = json.parseToJsonElement(raw).jsonObject

        assertEquals(3, tree["hidden_upstream"]!!.jsonPrimitive.content.toInt())
        assertEquals(5, tree["hidden_downstream"]!!.jsonPrimitive.content.toInt())
        assertFalse("hiddenUpstream" in tree, "camelCase form must not appear")
        assertFalse("hiddenDownstream" in tree, "camelCase form must not appear")
        // Sanity: also assert the literal substring the wire format must contain.
        assertTrue(
            raw.contains("\"hidden_upstream\":3") && raw.contains("\"hidden_downstream\":5"),
            "expected snake_case integer fields in encoded JSON: $raw",
        )
    }

    @Test
    fun `DbtModel hidden counts round-trip through encode and decode`() {
        val original = DbtModel(
            uniqueId = "model.demo.orders",
            name = "orders",
            packageName = "demo",
            hiddenUpstream = 7,
            hiddenDownstream = 11,
        )
        val decoded = json.decodeFromString(
            DbtModel.serializer(),
            json.encodeToString(DbtModel.serializer(), original),
        )
        assertEquals(7, decoded.hiddenUpstream)
        assertEquals(11, decoded.hiddenDownstream)
        assertEquals(original, decoded)
    }

    @Test
    fun `DbtModel default hidden counts are emitted as 0 (encodeDefaults=true)`() {
        // LineageJson uses `encodeDefaults = true`, so even default-valued
        // Int fields appear in the wire output. The frontend relies on
        // this — `payload.hidden_upstream ?? 0` would also work, but we
        // pin the actual emitted shape here so a future config flip to
        // `encodeDefaults = false` can't silently ship and break the chip.
        val model = DbtModel(uniqueId = "uid", name = "n", packageName = "demo")
        val raw = json.encodeToString(DbtModel.serializer(), model)
        val tree = json.parseToJsonElement(raw).jsonObject

        assertTrue("hidden_upstream" in tree, "default Int still emitted under encodeDefaults=true")
        assertTrue("hidden_downstream" in tree, "default Int still emitted under encodeDefaults=true")
        assertEquals(0, tree["hidden_upstream"]!!.jsonPrimitive.content.toInt())
        assertEquals(0, tree["hidden_downstream"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `round-trip of a full payload preserves all fields`() {
        val original = LineagePayload(
            models = listOf(
                DbtModel(
                    uniqueId = "model.demo.stg_orders",
                    name = "stg_orders",
                    packageName = "demo",
                    layer = "staging",
                    columns = listOf(
                        ColumnSpec("id", "bigint", "primary key"),
                        ColumnSpec("amount", "numeric", null),
                    ),
                ),
                DbtModel(
                    uniqueId = "model.demo.orders",
                    name = "orders",
                    packageName = "demo",
                    layer = "marts",
                ),
            ),
            modelEdges = listOf(ModelEdge("model.demo.stg_orders", "model.demo.orders")),
            columnEdges = listOf(
                ColumnEdge(
                    sourceUniqueId = "model.demo.stg_orders",
                    sourceColumn = "id",
                    targetUniqueId = "model.demo.orders",
                    targetColumn = "id",
                    expression = "id",
                ),
            ),
            selected = Selected(uniqueId = "model.demo.orders", column = "id"),
        )

        val encoded = json.encodeToString(LineagePayload.serializer(), original)
        val decoded = json.decodeFromString(LineagePayload.serializer(), encoded)

        assertEquals(original, decoded)
    }
}
