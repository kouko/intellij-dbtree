package dev.kouko.intellijdbtree.lineage

import com.google.gson.JsonParser
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for the pure column-lineage stitching logic in
 * [ColumnLineageWalker]. The recursion engines accept a sidecar-call
 * lambda, so we feed deterministic stubs without spawning Python.
 */
class ColumnLineageWalkerTest {

    @Nested
    inner class ExtractTableName {
        @Test
        fun `bare name`() {
            assertEquals("orders", extractTableName("orders"))
        }

        @Test
        fun `schema-qualified strips schema`() {
            assertEquals("orders", extractTableName("public.orders"))
        }

        @Test
        fun `db-schema-qualified strips both`() {
            assertEquals("orders", extractTableName("warehouse.public.orders"))
        }

        @Test
        fun `quoted identifiers are unquoted`() {
            assertEquals("orders", extractTableName("\"warehouse\".\"public\".\"orders\""))
            assertEquals("orders", extractTableName("`warehouse`.`public`.`orders`"))
        }

        @Test
        fun `alias is dropped`() {
            assertEquals(
                "stg_orders",
                extractTableName("\"jaffle_shop\".\"main\".\"stg_orders\" AS stg_orders"),
            )
        }

        @Test
        fun `empty input returns null`() {
            assertNull(extractTableName(""))
            assertNull(extractTableName("   "))
        }

        @Test
        fun `alias-only input returns null`() {
            // Edge case: " AS x" with nothing before it.
            assertNull(extractTableName(" AS alias"))
        }
    }

    @Nested
    inner class ResolveSource {

        private val manifest = ParsedManifest(
            JsonParser.parseString(
                """
                {
                  "nodes": {
                    "model.demo.orders": {
                      "resource_type": "model",
                      "name": "orders",
                      "package_name": "demo"
                    }
                  },
                  "sources": {
                    "source.demo.app.users": {
                      "resource_type": "source",
                      "name": "users",
                      "package_name": "demo"
                    }
                  }
                }
                """.trimIndent(),
            ).asJsonObject,
            catalog = null,
            dbtProjectDir = Path.of("/dbt"),
        )

        @Test
        fun `matches a model by table name`() {
            val sc = SourceColumn(table = "\"db\".\"main\".\"orders\" AS orders", column = "orders.id")
            assertEquals("model.demo.orders" to "id", resolveSource(sc, manifest))
        }

        @Test
        fun `matches a source by table name`() {
            val sc = SourceColumn(table = "\"raw\".\"app\".\"users\"", column = "id")
            assertEquals("source.demo.app.users" to "id", resolveSource(sc, manifest))
        }

        @Test
        fun `unknown table returns null`() {
            val sc = SourceColumn(table = "\"db\".\"main\".\"unknown_table\"", column = "id")
            assertNull(resolveSource(sc, manifest))
        }

        @Test
        fun `column with alias prefix is reduced to bare column`() {
            // sqlglot reports "stg_orders.amount" — we want just "amount".
            val sc = SourceColumn(table = "\"db\".\"main\".\"orders\"", column = "stg_orders.amount")
            assertEquals("model.demo.orders" to "amount", resolveSource(sc, manifest))
        }
    }

    @Nested
    inner class TraceUpstream {

        // Manifest with chain:  source.demo.app.raw_orders -> stg_orders -> orders
        private val manifest = ParsedManifest(
            JsonParser.parseString(
                """
                {
                  "nodes": {
                    "model.demo.stg_orders": {
                      "resource_type": "model",
                      "name": "stg_orders",
                      "package_name": "demo"
                    },
                    "model.demo.orders": {
                      "resource_type": "model",
                      "name": "orders",
                      "package_name": "demo"
                    }
                  },
                  "sources": {
                    "source.demo.app.raw_orders": {
                      "resource_type": "source",
                      "name": "raw_orders",
                      "package_name": "demo"
                    }
                  }
                }
                """.trimIndent(),
            ).asJsonObject,
            catalog = null,
            dbtProjectDir = Path.of("/dbt"),
        )

        @Test
        fun `single-hop trace from model to upstream model`() {
            // orders.id ← stg_orders.id
            val callSidecar: (String, String) -> SidecarResult? = { uid, col ->
                if (uid == "model.demo.orders" && col == "id") {
                    SidecarResult(
                        column = "id",
                        lineage = SidecarNode(name = "id", sourceType = "COLUMN", expression = "id"),
                        sourceColumns = listOf(
                            SourceColumn(table = "\"db\".\"main\".\"stg_orders\"", column = "id"),
                        ),
                    )
                } else if (uid == "model.demo.stg_orders") {
                    SidecarResult(
                        column = "id",
                        lineage = SidecarNode(name = "id", sourceType = "COLUMN"),
                        sourceColumns = emptyList(),
                    )
                } else null
            }

            val edges = traceUpstreamColumns(
                seedModelUid = "model.demo.orders",
                seedColumn = "id",
                manifest = manifest,
                callSidecar = callSidecar,
            )
            assertEquals(1, edges.size)
            assertEquals(
                ColumnEdge(
                    sourceUniqueId = "model.demo.stg_orders",
                    sourceColumn = "id",
                    targetUniqueId = "model.demo.orders",
                    targetColumn = "id",
                    expression = "id",
                ),
                edges.single(),
            )
        }

        @Test
        fun `multi-hop trace stitches model to model to source`() {
            // orders.id ← stg_orders.id ← raw_orders.id
            val callSidecar: (String, String) -> SidecarResult? = { uid, col ->
                when (uid) {
                    "model.demo.orders" -> SidecarResult(
                        column = col,
                        lineage = SidecarNode(name = col, sourceType = "COLUMN"),
                        sourceColumns = listOf(SourceColumn("\"d\".\"s\".\"stg_orders\"", "id")),
                    )
                    "model.demo.stg_orders" -> SidecarResult(
                        column = col,
                        lineage = SidecarNode(name = col, sourceType = "COLUMN"),
                        sourceColumns = listOf(SourceColumn("\"d\".\"s\".\"raw_orders\"", "id")),
                    )
                    else -> null
                }
            }

            val edges = traceUpstreamColumns("model.demo.orders", "id", manifest, callSidecar)
            assertEquals(2, edges.size)
            assertContains(
                edges,
                ColumnEdge(
                    sourceUniqueId = "model.demo.stg_orders",
                    sourceColumn = "id",
                    targetUniqueId = "model.demo.orders",
                    targetColumn = "id",
                ),
            )
            assertContains(
                edges,
                ColumnEdge(
                    sourceUniqueId = "source.demo.app.raw_orders",
                    sourceColumn = "id",
                    targetUniqueId = "model.demo.stg_orders",
                    targetColumn = "id",
                ),
            )
        }

        @Test
        fun `does not recurse past a source (regression)`() {
            // The real bug: sidecar would crash on "No model named source.X"
            // if we recursed into source uids. The walker must stop at source.*.
            val seenCalls = mutableListOf<Pair<String, String>>()
            val callSidecar: (String, String) -> SidecarResult? = { uid, col ->
                seenCalls += uid to col
                if (uid == "model.demo.stg_orders") {
                    SidecarResult(
                        column = col,
                        lineage = SidecarNode(name = col, sourceType = "COLUMN"),
                        sourceColumns = listOf(SourceColumn("\"d\".\"s\".\"raw_orders\"", "id")),
                    )
                } else null
            }

            traceUpstreamColumns("model.demo.stg_orders", "id", manifest, callSidecar)
            // We called sidecar for stg_orders only — NOT for source.demo.app.raw_orders.
            assertEquals(listOf("model.demo.stg_orders" to "id"), seenCalls)
        }

        @Test
        fun `cycle protection (visited set) prevents infinite recursion`() {
            // Pathological: a column reports itself as its own source.
            val callCount = mutableMapOf<Pair<String, String>, Int>()
            val callSidecar: (String, String) -> SidecarResult? = { uid, col ->
                val key = uid to col
                callCount[key] = (callCount[key] ?: 0) + 1
                SidecarResult(
                    column = col,
                    lineage = SidecarNode(name = col, sourceType = "COLUMN"),
                    sourceColumns = listOf(SourceColumn("\"d\".\"s\".\"orders\"", col)),
                )
            }
            traceUpstreamColumns("model.demo.orders", "id", manifest, callSidecar)
            assertEquals(1, callCount["model.demo.orders" to "id"])
        }

        @Test
        fun `sidecar returns null yields no edges (no crash)`() {
            val edges = traceUpstreamColumns(
                seedModelUid = "model.demo.orders",
                seedColumn = "id",
                manifest = manifest,
                callSidecar = { _, _ -> null },
            )
            assertTrue(edges.isEmpty())
        }
    }

    @Nested
    inner class TraceDownstream {

        // Manifest:  stg_orders -> orders -> orders_summary
        // child_map drives the downstream walk; modelName drives matching.
        private val manifest = ParsedManifest(
            JsonParser.parseString(
                """
                {
                  "nodes": {
                    "model.demo.stg_orders": {
                      "resource_type": "model", "name": "stg_orders", "package_name": "demo"
                    },
                    "model.demo.orders": {
                      "resource_type": "model", "name": "orders", "package_name": "demo"
                    },
                    "model.demo.orders_summary": {
                      "resource_type": "model", "name": "orders_summary", "package_name": "demo"
                    }
                  },
                  "sources": {},
                  "child_map": {
                    "model.demo.stg_orders": ["model.demo.orders"],
                    "model.demo.orders": ["model.demo.orders_summary"],
                    "model.demo.orders_summary": []
                  },
                  "parent_map": {
                    "model.demo.stg_orders": [],
                    "model.demo.orders": ["model.demo.stg_orders"],
                    "model.demo.orders_summary": ["model.demo.orders"]
                  }
                }
                """.trimIndent(),
            ).asJsonObject,
            catalog = null,
            dbtProjectDir = Path.of("/dbt"),
        )

        @Test
        fun `walks one direct child and finds the matching column`() {
            // Seed: stg_orders.id. Child: orders.id sources from stg_orders.id.
            val callSidecar: (String) -> AllColumnsResult? = { uid ->
                if (uid == "model.demo.orders") {
                    AllColumnsResult(
                        columns = listOf(
                            ColumnEntry(
                                column = "id",
                                lineage = SidecarNode(name = "id", sourceType = "COLUMN", expression = "id"),
                                sourceColumns = listOf(SourceColumn("\"d\".\"s\".\"stg_orders\"", "stg_orders.id")),
                            ),
                            ColumnEntry(
                                column = "amount",
                                lineage = SidecarNode(name = "amount", sourceType = "COLUMN"),
                                sourceColumns = listOf(SourceColumn("\"d\".\"s\".\"stg_orders\"", "stg_orders.amount")),
                            ),
                        ),
                    )
                } else null
            }

            val edges = traceDownstreamColumns("model.demo.stg_orders", "id", manifest, callSidecar)
            assertEquals(1, edges.size, "only the matching column produces an edge")
            assertEquals(
                ColumnEdge(
                    sourceUniqueId = "model.demo.stg_orders",
                    sourceColumn = "id",
                    targetUniqueId = "model.demo.orders",
                    targetColumn = "id",
                    expression = "id",
                ),
                edges.single(),
            )
        }

        @Test
        fun `recurses across multiple downstream hops`() {
            val callSidecar: (String) -> AllColumnsResult? = { uid ->
                when (uid) {
                    "model.demo.orders" -> AllColumnsResult(
                        columns = listOf(
                            ColumnEntry(
                                column = "id",
                                lineage = SidecarNode("id", "COLUMN"),
                                sourceColumns = listOf(SourceColumn("\"d\".\"s\".\"stg_orders\"", "stg_orders.id")),
                            ),
                        ),
                    )
                    "model.demo.orders_summary" -> AllColumnsResult(
                        columns = listOf(
                            ColumnEntry(
                                column = "order_id",
                                lineage = SidecarNode("order_id", "COLUMN"),
                                sourceColumns = listOf(SourceColumn("\"d\".\"s\".\"orders\"", "orders.id")),
                            ),
                        ),
                    )
                    else -> null
                }
            }

            val edges = traceDownstreamColumns("model.demo.stg_orders", "id", manifest, callSidecar)
            // Two hops: stg_orders.id -> orders.id -> orders_summary.order_id
            assertEquals(2, edges.size)
            assertContains(
                edges,
                ColumnEdge(
                    sourceUniqueId = "model.demo.stg_orders",
                    sourceColumn = "id",
                    targetUniqueId = "model.demo.orders",
                    targetColumn = "id",
                ),
            )
            assertContains(
                edges,
                ColumnEdge(
                    sourceUniqueId = "model.demo.orders",
                    sourceColumn = "id",
                    targetUniqueId = "model.demo.orders_summary",
                    targetColumn = "order_id",
                ),
            )
        }

        @Test
        fun `seed at a leaf (no children) yields no edges`() {
            val callSidecar: (String) -> AllColumnsResult? = { _ -> error("should not be called") }
            val edges = traceDownstreamColumns("model.demo.orders_summary", "order_id", manifest, callSidecar)
            assertTrue(edges.isEmpty())
        }

        @Test
        fun `child column whose source does not match the seed is skipped`() {
            // orders.unrelated comes from somewhere else, not stg_orders.id.
            val callSidecar: (String) -> AllColumnsResult? = { uid ->
                if (uid == "model.demo.orders") {
                    AllColumnsResult(
                        columns = listOf(
                            ColumnEntry(
                                column = "unrelated",
                                lineage = SidecarNode("unrelated", "COLUMN"),
                                sourceColumns = listOf(SourceColumn("\"d\".\"s\".\"some_other\"", "x")),
                            ),
                        ),
                    )
                } else null
            }
            val edges = traceDownstreamColumns("model.demo.stg_orders", "id", manifest, callSidecar)
            assertTrue(edges.isEmpty())
        }

        @Test
        fun `sidecar returns null for a child yields no crash`() {
            val edges = traceDownstreamColumns(
                seedModelUid = "model.demo.stg_orders",
                seedColumn = "id",
                manifest = manifest,
                callSidecar = { _ -> null },
            )
            assertTrue(edges.isEmpty())
        }
    }
}
