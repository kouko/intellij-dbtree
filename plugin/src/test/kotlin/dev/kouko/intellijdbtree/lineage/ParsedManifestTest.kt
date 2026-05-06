package dev.kouko.intellijdbtree.lineage

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure unit tests over [ParsedManifest] using crafted manifest.json fragments.
 *
 * Avoids `BasePlatformTestCase` — `ParsedManifest` is a plain data class
 * over Gson's [JsonObject] and a [Path], so we can construct one directly.
 */
class ParsedManifestTest {

    @Nested
    inner class BuildLineageTests {

        // Linear chain: stg_orders → orders → orders_summary
        // (stg_orders depends on the source sources.app.app_orders to add a source node)
        private val linearChain = parseManifest(
            """
            {
              "nodes": {
                "model.demo.stg_orders":      ${node("stg_orders", layerPath = "staging/stg_orders.sql")},
                "model.demo.orders":          ${node("orders", layerPath = "marts/orders.sql")},
                "model.demo.orders_summary":  ${node("orders_summary", layerPath = "marts/orders_summary.sql")}
              },
              "sources": {
                "source.demo.app.app_orders": ${source("app_orders")}
              },
              "child_map": {
                "source.demo.app.app_orders": ["model.demo.stg_orders"],
                "model.demo.stg_orders":      ["model.demo.orders"],
                "model.demo.orders":          ["model.demo.orders_summary"],
                "model.demo.orders_summary":  []
              },
              "parent_map": {
                "model.demo.stg_orders":     ["source.demo.app.app_orders"],
                "model.demo.orders":         ["model.demo.stg_orders"],
                "model.demo.orders_summary": ["model.demo.orders"],
                "source.demo.app.app_orders":[]
              }
            }
            """.trimIndent(),
        )

        @Test
        fun `linear chain seed in middle returns full chain at unlimited hops`() {
            val payload = linearChain.buildLineage(seed = "model.demo.orders")
            val ids = payload.models.map { it.uniqueId }.toSet()
            assertEquals(
                setOf(
                    "source.demo.app.app_orders",
                    "model.demo.stg_orders",
                    "model.demo.orders",
                    "model.demo.orders_summary",
                ),
                ids,
            )
            assertEquals(3, payload.modelEdges.size)
            assertEquals(payload.selected?.uniqueId, "model.demo.orders")
            assertNull(payload.selected?.column)
        }

        @Test
        fun `upHops=1 limits upstream walk to direct parent`() {
            val payload = linearChain.buildLineage(
                seed = "model.demo.orders",
                upHops = 1,
                downHops = Int.MAX_VALUE,
            )
            val ids = payload.models.map { it.uniqueId }.toSet()
            assertEquals(
                setOf(
                    "model.demo.stg_orders",
                    "model.demo.orders",
                    "model.demo.orders_summary",
                ),
                ids,
            )
            assertFalse("source.demo.app.app_orders" in ids)
        }

        @Test
        fun `downHops=1 limits downstream walk to direct child`() {
            val payload = linearChain.buildLineage(
                seed = "model.demo.stg_orders",
                upHops = Int.MAX_VALUE,
                downHops = 1,
            )
            val ids = payload.models.map { it.uniqueId }.toSet()
            assertEquals(
                setOf(
                    "source.demo.app.app_orders",
                    "model.demo.stg_orders",
                    "model.demo.orders",
                ),
                ids,
            )
            assertFalse("model.demo.orders_summary" in ids)
        }

        @Test
        fun `upHops=0 returns seed plus descendants only`() {
            val payload = linearChain.buildLineage(
                seed = "model.demo.orders",
                upHops = 0,
                downHops = Int.MAX_VALUE,
            )
            val ids = payload.models.map { it.uniqueId }.toSet()
            assertEquals(
                setOf("model.demo.orders", "model.demo.orders_summary"),
                ids,
            )
        }

        @Test
        fun `downHops=0 returns seed plus ancestors only`() {
            val payload = linearChain.buildLineage(
                seed = "model.demo.orders",
                upHops = Int.MAX_VALUE,
                downHops = 0,
            )
            val ids = payload.models.map { it.uniqueId }.toSet()
            assertEquals(
                setOf("source.demo.app.app_orders", "model.demo.stg_orders", "model.demo.orders"),
                ids,
            )
        }

        // The diamond regression. Real shape:
        //
        //     orders ─┬─→ customers ─→ customer_combined_metrics
        //             └────────────────────↗
        //
        // orders is BOTH a parent of customers AND a direct parent of
        // customer_combined_metrics. With seed=customers, walkUp reaches
        // orders and walkDown reaches customer_combined_metrics, but the
        // direct orders → customer_combined_metrics edge bypasses the seed.
        // Without the post-pass, that edge is dropped from the DAG even
        // though both endpoints are in `visited`.
        @Test
        fun `diamond edge bypassing seed is preserved`() {
            val manifest = parseManifest(
                """
                {
                  "nodes": {
                    "model.demo.orders":                     ${node("orders")},
                    "model.demo.customers":                  ${node("customers")},
                    "model.demo.customer_combined_metrics":  ${node("customer_combined_metrics")}
                  },
                  "sources": {},
                  "child_map": {
                    "model.demo.orders": [
                      "model.demo.customers",
                      "model.demo.customer_combined_metrics"
                    ],
                    "model.demo.customers": ["model.demo.customer_combined_metrics"],
                    "model.demo.customer_combined_metrics": []
                  },
                  "parent_map": {
                    "model.demo.orders": [],
                    "model.demo.customers": ["model.demo.orders"],
                    "model.demo.customer_combined_metrics": [
                      "model.demo.customers", "model.demo.orders"
                    ]
                  }
                }
                """.trimIndent(),
            )

            val payload = manifest.buildLineage(seed = "model.demo.customers")

            // All three models must be in the visited subgraph
            val ids = payload.models.map { it.uniqueId }.toSet()
            assertEquals(
                setOf(
                    "model.demo.orders",
                    "model.demo.customers",
                    "model.demo.customer_combined_metrics",
                ),
                ids,
            )
            // Direct skip edge orders → customer_combined_metrics must be
            // picked up by the post-pass even though it bypasses the seed.
            assertContains(
                payload.modelEdges,
                ModelEdge("model.demo.orders", "model.demo.customer_combined_metrics"),
                "skip edge bypassing the seed must be preserved (diamond regression)",
            )
            // Sanity: the two non-skip edges are present too.
            assertContains(
                payload.modelEdges,
                ModelEdge("model.demo.orders", "model.demo.customers"),
            )
            assertContains(
                payload.modelEdges,
                ModelEdge("model.demo.customers", "model.demo.customer_combined_metrics"),
            )
        }

        @Test
        fun `seed=null returns full project graph`() {
            val payload = linearChain.buildLineage(seed = null)
            assertEquals(4, payload.models.size)
            assertNull(payload.selected, "no seed = no selection")
        }

        @Test
        fun `tests and seeds are filtered out of interesting nodes`() {
            val manifest = parseManifest(
                """
                {
                  "nodes": {
                    "model.demo.orders":                ${node("orders")},
                    "test.demo.unique_orders_id":       ${node("unique_orders_id", resourceType = "test")},
                    "seed.demo.country_codes":          ${node("country_codes", resourceType = "seed")},
                    "snapshot.demo.orders_snapshot":    ${node("orders_snapshot", resourceType = "snapshot")}
                  },
                  "sources": {},
                  "child_map": {
                    "model.demo.orders": ["test.demo.unique_orders_id", "snapshot.demo.orders_snapshot"]
                  },
                  "parent_map": {
                    "test.demo.unique_orders_id": ["model.demo.orders"],
                    "snapshot.demo.orders_snapshot": ["model.demo.orders"]
                  }
                }
                """.trimIndent(),
            )

            val payload = manifest.buildLineage(seed = "model.demo.orders")
            val ids = payload.models.map { it.uniqueId }.toSet()
            assertEquals(setOf("model.demo.orders"), ids)
            assertTrue(payload.modelEdges.isEmpty(), "edges to tests/snapshots are dropped")
        }

        @Test
        fun `sources appear in lineage with layer=source`() {
            val payload = linearChain.buildLineage(seed = "model.demo.stg_orders")
            val sourceModel = payload.models.single { it.uniqueId == "source.demo.app.app_orders" }
            assertEquals("source", sourceModel.layer)
            assertEquals("app_orders", sourceModel.name)
        }

        @Test
        fun `seed not in manifest returns empty payload (no crash)`() {
            val payload = linearChain.buildLineage(seed = "model.does.not.exist")
            // Seed gets added to visited and will appear in models if describable;
            // since it doesn't exist in nodes/sources, it's filtered out.
            assertTrue(payload.models.isEmpty())
            assertNull(payload.selected, "non-existent seed yields no Selected")
        }
    }

    @Nested
    inner class LookupTests {

        private val manifest = parseManifest(
            """
            {
              "nodes": {
                "model.demo.orders":     ${node("orders")},
                "model.demo.customers":  ${node("customers")},
                "test.demo.t_orders":    ${node("t_orders", resourceType = "test")}
              },
              "sources": {
                "source.demo.app.users": ${source("users")}
              },
              "child_map": {
                "model.demo.orders": ["model.demo.customers", "test.demo.t_orders"]
              },
              "parent_map": {
                "model.demo.customers": ["model.demo.orders"],
                "test.demo.t_orders":   ["model.demo.orders"]
              }
            }
            """.trimIndent(),
        )

        @Test
        fun `findModelByName matches model bare name`() {
            assertEquals("model.demo.orders", manifest.findModelByName("orders"))
            assertEquals("model.demo.customers", manifest.findModelByName("customers"))
        }

        @Test
        fun `findModelByName returns null for unknown name`() {
            assertNull(manifest.findModelByName("nonexistent"))
        }

        @Test
        fun `findModelByName does not match sources or tests`() {
            // 'users' is a source name, not a model
            assertNull(manifest.findModelByName("users"))
            // 't_orders' is a test
            assertNull(manifest.findModelByName("t_orders"))
        }

        @Test
        fun `findSourceByName matches source bare name`() {
            assertEquals("source.demo.app.users", manifest.findSourceByName("users"))
        }

        @Test
        fun `findSourceByName returns null for unknown`() {
            assertNull(manifest.findSourceByName("nonexistent"))
        }

        @Test
        fun `directChildren returns models only, not tests`() {
            val children = manifest.directChildren("model.demo.orders")
            assertEquals(listOf("model.demo.customers"), children)
        }

        @Test
        fun `directChildren of leaf model is empty`() {
            assertTrue(manifest.directChildren("model.demo.customers").isEmpty())
        }

        @Test
        fun `directChildren of unknown returns empty`() {
            assertTrue(manifest.directChildren("model.foo.bar").isEmpty())
        }

        @Test
        fun `modelName returns name for model uid`() {
            assertEquals("orders", manifest.modelName("model.demo.orders"))
        }

        @Test
        fun `modelName returns null for source or test`() {
            assertNull(manifest.modelName("source.demo.app.users"))
            assertNull(manifest.modelName("test.demo.t_orders"))
        }

        @Test
        fun `lookupOriginalPath returns relative path`() {
            // node() helper sets original_file_path = "models/$name.sql"
            assertEquals("models/orders.sql", manifest.lookupOriginalPath("model.demo.orders"))
        }

        @Test
        fun `resolveByOriginalPath maps absolute file path to uid`() {
            val abs = "/dbt/models/orders.sql"
            assertEquals("model.demo.orders", manifest.resolveByOriginalPath(abs))
        }

        @Test
        fun `resolveByOriginalPath returns null for non-model file`() {
            assertNull(manifest.resolveByOriginalPath("/dbt/something/else.sql"))
        }
    }

    @Nested
    inner class ColumnMergeTests {

        @Test
        fun `catalog provides type, manifest provides description`() {
            val manifest = parseManifest(
                manifestJson = """
                    {
                      "nodes": {
                        "model.demo.orders": ${
                            node(
                                "orders",
                                columnsJson = """
                                    {
                                      "id":   {"name": "id",   "description": "primary key"},
                                      "name": {"name": "name", "description": "customer name", "data_type": "varchar"}
                                    }
                                """.trimIndent(),
                            )
                        }
                      },
                      "sources": {},
                      "child_map": {},
                      "parent_map": {}
                    }
                """.trimIndent(),
                catalogJson = """
                    {
                      "nodes": {
                        "model.demo.orders": {
                          "columns": {
                            "id":   {"type": "bigint",   "index": 1},
                            "name": {"type": "string",   "index": 2}
                          }
                        }
                      },
                      "sources": {}
                    }
                """.trimIndent(),
            )
            val payload = manifest.buildLineage(seed = "model.demo.orders")
            val cols = payload.models.single().columns.associateBy { it.name }
            assertEquals("bigint", cols["id"]!!.type, "type comes from catalog")
            assertEquals("primary key", cols["id"]!!.description, "description comes from manifest")
            assertEquals("string", cols["name"]!!.type, "catalog type wins over manifest data_type")
            assertEquals("customer name", cols["name"]!!.description)
        }

        @Test
        fun `manifest-only column appears (no catalog row)`() {
            val manifest = parseManifest(
                """
                {
                  "nodes": {
                    "model.demo.orders": ${
                        node(
                            "orders",
                            columnsJson = """
                                {
                                  "manifest_only": {"name": "manifest_only", "data_type": "text"}
                                }
                            """.trimIndent(),
                        )
                    }
                  },
                  "sources": {},
                  "child_map": {},
                  "parent_map": {}
                }
                """.trimIndent(),
            )
            val payload = manifest.buildLineage(seed = "model.demo.orders")
            val col = payload.models.single().columns.single()
            assertEquals("manifest_only", col.name)
            assertEquals("text", col.type, "falls back to manifest data_type when no catalog")
        }

        @Test
        fun `catalog-only column appears (no manifest row)`() {
            val manifest = parseManifest(
                manifestJson = """
                    {
                      "nodes": {
                        "model.demo.orders": ${node("orders", columnsJson = "{}")}
                      },
                      "sources": {},
                      "child_map": {},
                      "parent_map": {}
                    }
                """.trimIndent(),
                catalogJson = """
                    {
                      "nodes": {
                        "model.demo.orders": {
                          "columns": {"warehouse_only": {"type": "int", "index": 1}}
                        }
                      },
                      "sources": {}
                    }
                """.trimIndent(),
            )
            val payload = manifest.buildLineage(seed = "model.demo.orders")
            val col = payload.models.single().columns.single()
            assertEquals("warehouse_only", col.name)
            assertEquals("int", col.type)
        }

        @Test
        fun `catalog index controls column order`() {
            val manifest = parseManifest(
                manifestJson = """
                    {
                      "nodes": {
                        "model.demo.orders": ${node("orders", columnsJson = "{}")}
                      },
                      "sources": {},
                      "child_map": {},
                      "parent_map": {}
                    }
                """.trimIndent(),
                catalogJson = """
                    {
                      "nodes": {
                        "model.demo.orders": {
                          "columns": {
                            "second": {"type": "int", "index": 2},
                            "first":  {"type": "int", "index": 1},
                            "third":  {"type": "int", "index": 3}
                          }
                        }
                      },
                      "sources": {}
                    }
                """.trimIndent(),
            )
            val payload = manifest.buildLineage(seed = "model.demo.orders")
            val names = payload.models.single().columns.map { it.name }
            assertEquals(listOf("first", "second", "third"), names)
        }

        @Test
        fun `model with no columns returns empty list`() {
            val manifest = parseManifest(
                """
                {
                  "nodes": { "model.demo.orders": ${node("orders")} },
                  "sources": {},
                  "child_map": {},
                  "parent_map": {}
                }
                """.trimIndent(),
            )
            val payload = manifest.buildLineage(seed = "model.demo.orders")
            assertTrue(payload.models.single().columns.isEmpty())
        }

        @Test
        fun `source columns also merge from catalogSources`() {
            val manifest = parseManifest(
                manifestJson = """
                    {
                      "nodes": {},
                      "sources": {
                        "source.demo.app.users": ${
                            source(
                                "users",
                                columnsJson = """
                                    {"id": {"name": "id", "description": "user id"}}
                                """.trimIndent(),
                            )
                        }
                      },
                      "child_map": {},
                      "parent_map": {}
                    }
                """.trimIndent(),
                catalogJson = """
                    {
                      "nodes": {},
                      "sources": {
                        "source.demo.app.users": {
                          "columns": {"id": {"type": "uuid", "index": 1}}
                        }
                      }
                    }
                """.trimIndent(),
            )
            val payload = manifest.buildLineage(seed = null)
            val source = payload.models.single { it.uniqueId == "source.demo.app.users" }
            val col = source.columns.single()
            assertEquals("uuid", col.type, "source.type comes from catalogSources")
            assertEquals("user id", col.description)
        }
    }

    @Nested
    inner class LayerInferenceTests {

        @Test
        fun `staging directory implies staging layer`() {
            assertEquals("staging", layerFor("staging/stg_orders.sql"))
        }

        @Test
        fun `stg-prefix file at root implies staging layer`() {
            assertEquals("staging", layerFor("stg_orders.sql"))
        }

        @Test
        fun `intermediate directory implies intermediate layer`() {
            assertEquals("intermediate", layerFor("intermediate/int_orders.sql"))
        }

        @Test
        fun `int-prefix file at root implies intermediate layer`() {
            assertEquals("intermediate", layerFor("int_orders.sql"))
        }

        @Test
        fun `marts directory implies marts layer`() {
            assertEquals("marts", layerFor("marts/orders.sql"))
        }

        @Test
        fun `mart (no s) directory also implies marts layer`() {
            assertEquals("marts", layerFor("mart/orders.sql"))
        }

        @Test
        fun `models directory implies marts layer`() {
            assertEquals("marts", layerFor("models/orders.sql"))
        }

        @Test
        fun `unknown first segment is returned as-is`() {
            assertEquals("custom", layerFor("custom/orders.sql"))
        }

        @Test
        fun `model with no path field returns null layer`() {
            val manifest = parseManifest(
                """
                {
                  "nodes": {
                    "model.demo.orders": {
                      "resource_type": "model",
                      "name": "orders",
                      "package_name": "demo",
                      "original_file_path": "models/orders.sql"
                    }
                  },
                  "sources": {},
                  "child_map": {},
                  "parent_map": {}
                }
                """.trimIndent(),
            )
            val payload = manifest.buildLineage(seed = "model.demo.orders")
            assertNull(payload.models.single().layer)
        }

        private fun layerFor(path: String): String? {
            val manifest = parseManifest(
                """
                {
                  "nodes": {
                    "model.demo.x": ${node("x", layerPath = path)}
                  },
                  "sources": {},
                  "child_map": {},
                  "parent_map": {}
                }
                """.trimIndent(),
            )
            return manifest.buildLineage(seed = "model.demo.x").models.single().layer
        }
    }

    // ---- helpers --------------------------------------------------------

    private fun parseManifest(
        manifestJson: String,
        catalogJson: String? = null,
    ): ParsedManifest {
        val manifest = JsonParser.parseString(manifestJson).asJsonObject
        val catalog: JsonObject? = catalogJson?.let { JsonParser.parseString(it).asJsonObject }
        return ParsedManifest(manifest, catalog, Path.of("/dbt"))
    }

    /**
     * Build a minimal model node JSON with sensible defaults.
     * `layerPath` controls the `path` field used by inferLayer.
     */
    private fun node(
        name: String,
        resourceType: String = "model",
        layerPath: String = "models/$name.sql",
        columnsJson: String? = null,
    ): String {
        val cols = if (columnsJson != null) """, "columns": $columnsJson""" else ""
        return """
            {
              "resource_type": "$resourceType",
              "name": "$name",
              "package_name": "demo",
              "path": "$layerPath",
              "original_file_path": "models/$name.sql"$cols
            }
        """.trimIndent()
    }

    private fun source(
        name: String,
        columnsJson: String? = null,
    ): String {
        val cols = if (columnsJson != null) """, "columns": $columnsJson""" else ""
        return """
            {
              "resource_type": "source",
              "name": "$name",
              "package_name": "demo"$cols
            }
        """.trimIndent()
    }
}
