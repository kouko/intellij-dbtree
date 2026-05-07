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
        fun `skip-edge re-entry preserves shortest-path reachability under hop budget`() {
            // Regression guard: A→B→C→D→E plus a skip edge A→C. With downHops=3
            // and seed=A, all 5 nodes should be visible because the skip edge
            // A→C re-enters C with `remaining=2` (vs the long path's `remaining=1`),
            // unlocking access to D→E within the hop budget.
            //
            // The behavior depends on the `|| remaining > 0` condition in walkDown.
            // If a future "cleanup" removes that OR (treating it as dead code),
            // this test will fail — and that's intentional. Removing the OR
            // breaks ~10% of iCHEF lineage queries because dbt projects commonly
            // mix shortcut refs into the dependency graph.
            val skipEdgeChain = parseManifest(
                """
                {
                  "nodes": {
                    "model.demo.a": ${node("a", layerPath = "staging/a.sql")},
                    "model.demo.b": ${node("b", layerPath = "intermediate/b.sql")},
                    "model.demo.c": ${node("c", layerPath = "intermediate/c.sql")},
                    "model.demo.d": ${node("d", layerPath = "marts/d.sql")},
                    "model.demo.e": ${node("e", layerPath = "marts/e.sql")}
                  },
                  "sources": {},
                  "child_map": {
                    "model.demo.a": ["model.demo.b", "model.demo.c"],
                    "model.demo.b": ["model.demo.c"],
                    "model.demo.c": ["model.demo.d"],
                    "model.demo.d": ["model.demo.e"],
                    "model.demo.e": []
                  },
                  "parent_map": {
                    "model.demo.a": [],
                    "model.demo.b": ["model.demo.a"],
                    "model.demo.c": ["model.demo.a", "model.demo.b"],
                    "model.demo.d": ["model.demo.c"],
                    "model.demo.e": ["model.demo.d"]
                  }
                }
                """.trimIndent(),
            )

            val payload = skipEdgeChain.buildLineage(
                seed = "model.demo.a",
                upHops = 0,
                downHops = 3,
            )
            val ids = payload.models.map { it.uniqueId }.toSet()
            assertEquals(
                setOf(
                    "model.demo.a",
                    "model.demo.b",
                    "model.demo.c",
                    "model.demo.d",
                    "model.demo.e",
                ),
                ids,
                "E must be reachable via the A→C shortcut despite the long path A→B→C→D exhausting budget first",
            )
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

    /**
     * Patterns observed in real production dbt projects (de-identified)
     * that the synthetic chain/diamond fixtures don't exercise:
     *
     * - **Sub-component aggregation**: a parent intermediate that consumes
     *   multiple sub-component intermediates AND the staging model directly
     *   — the dominant skip-edge shape we saw in real manifests (~9% of
     *   total edges).
     * - **Utility fan-out**: a staging model (date series, dimension table)
     *   ref'd directly by 5+ downstream models, some of which are also
     *   reachable via sibling intermediates.
     * - **Mart fan-in with upstream shortcut**: a mart whose ancestry
     *   spans 3+ layers, with at least one staging model bypassing the
     *   intermediate layer to ref the mart directly.
     * - **Multi-level shortcut interaction with hop budget**: shortcuts
     *   at different depths, exercised under varying upHops/downHops.
     *
     * These guard against regressions when "cleaning up" the BFS code:
     * the dominant real-world manifest topology must keep working, not
     * just textbook linear/diamond shapes.
     */
    @Nested
    inner class RealisticDbtPatternTests {

        @Test
        fun `sub-component aggregation pattern preserves all 4 nodes from staging seed`() {
            // Real shape (de-identified): a parent intermediate consumes
            // sub-component intermediates AND its source staging directly,
            // creating a 1-hop skip edge from staging to parent intermediate.
            //
            //   stg_listing
            //   ├─→ int_listing__hours
            //   │   └─→ int_listing       (long path)
            //   ├─→ int_listing__phone
            //   │   └─→ int_listing       (long path)
            //   └─→ int_listing            (direct, skip edge)
            val manifest = parseManifest(
                """
                {
                  "nodes": {
                    "model.demo.stg_listing":          ${node("stg_listing", layerPath = "staging/stg_listing.sql")},
                    "model.demo.int_listing__hours":   ${node("int_listing__hours", layerPath = "intermediate/int_listing__hours.sql")},
                    "model.demo.int_listing__phone":   ${node("int_listing__phone", layerPath = "intermediate/int_listing__phone.sql")},
                    "model.demo.int_listing":          ${node("int_listing", layerPath = "intermediate/int_listing.sql")}
                  },
                  "sources": {},
                  "child_map": {
                    "model.demo.stg_listing":        ["model.demo.int_listing__hours", "model.demo.int_listing__phone", "model.demo.int_listing"],
                    "model.demo.int_listing__hours": ["model.demo.int_listing"],
                    "model.demo.int_listing__phone": ["model.demo.int_listing"],
                    "model.demo.int_listing":        []
                  },
                  "parent_map": {
                    "model.demo.stg_listing":        [],
                    "model.demo.int_listing__hours": ["model.demo.stg_listing"],
                    "model.demo.int_listing__phone": ["model.demo.stg_listing"],
                    "model.demo.int_listing":        ["model.demo.stg_listing", "model.demo.int_listing__hours", "model.demo.int_listing__phone"]
                  }
                }
                """.trimIndent(),
            )
            val payload = manifest.buildLineage(
                seed = "model.demo.stg_listing",
                upHops = 0,
                downHops = 2,
            )
            val ids = payload.models.map { it.uniqueId }.toSet()
            assertEquals(
                setOf(
                    "model.demo.stg_listing",
                    "model.demo.int_listing__hours",
                    "model.demo.int_listing__phone",
                    "model.demo.int_listing",
                ),
                ids,
                "all 4 nodes must be visible — both sub-components AND the parent intermediate",
            )
            // The skip edge stg_listing → int_listing must be present alongside the
            // long-path edges, because dbt's manifest records both as direct refs.
            val edgePairs = payload.modelEdges.map { it.sourceUniqueId to it.targetUniqueId }.toSet()
            assertTrue(
                "model.demo.stg_listing" to "model.demo.int_listing" in edgePairs,
                "the skip edge stg_listing → int_listing must be preserved as-is",
            )
            assertTrue(
                "model.demo.int_listing__hours" to "model.demo.int_listing" in edgePairs,
                "the long-path edge through int_listing__hours must also be preserved",
            )
        }

        @Test
        fun `utility fan-out with mixed direct and transitive descendants`() {
            // Real shape: a "utility" staging model (e.g. date series, currency
            // dimension) with many direct children, some of which are also
            // reachable transitively via sibling intermediates.
            //
            //   stg_date_series
            //   ├─→ int_daily_a
            //   │   └─→ int_daily_b   (long path)
            //   ├─→ int_daily_b       (direct, skip edge)
            //   ├─→ int_daily_c
            //   └─→ mart_daily        (direct from utility — typical: marts often
            //                         ref date dimensions even when an int layer
            //                         already provides date columns)
            val manifest = parseManifest(
                """
                {
                  "nodes": {
                    "model.demo.stg_date_series": ${node("stg_date_series", layerPath = "staging/stg_date_series.sql")},
                    "model.demo.int_daily_a":     ${node("int_daily_a", layerPath = "intermediate/int_daily_a.sql")},
                    "model.demo.int_daily_b":     ${node("int_daily_b", layerPath = "intermediate/int_daily_b.sql")},
                    "model.demo.int_daily_c":     ${node("int_daily_c", layerPath = "intermediate/int_daily_c.sql")},
                    "model.demo.mart_daily":      ${node("mart_daily", layerPath = "marts/mart_daily.sql")}
                  },
                  "sources": {},
                  "child_map": {
                    "model.demo.stg_date_series": ["model.demo.int_daily_a", "model.demo.int_daily_b", "model.demo.int_daily_c", "model.demo.mart_daily"],
                    "model.demo.int_daily_a":     ["model.demo.int_daily_b"],
                    "model.demo.int_daily_b":     [],
                    "model.demo.int_daily_c":     [],
                    "model.demo.mart_daily":      []
                  },
                  "parent_map": {
                    "model.demo.stg_date_series": [],
                    "model.demo.int_daily_a":     ["model.demo.stg_date_series"],
                    "model.demo.int_daily_b":     ["model.demo.stg_date_series", "model.demo.int_daily_a"],
                    "model.demo.int_daily_c":     ["model.demo.stg_date_series"],
                    "model.demo.mart_daily":      ["model.demo.stg_date_series"]
                  }
                }
                """.trimIndent(),
            )
            val payload = manifest.buildLineage(
                seed = "model.demo.stg_date_series",
                upHops = 0,
                downHops = 2,
            )
            val ids = payload.models.map { it.uniqueId }.toSet()
            assertEquals(5, ids.size, "all 5 nodes (utility + 4 descendants) must appear")
            assertTrue("model.demo.mart_daily" in ids, "the cross-layer direct ref to mart must be visible")
            assertTrue("model.demo.int_daily_b" in ids, "int_daily_b reachable both directly and via int_daily_a")
        }

        @Test
        fun `mart fan-in with multi-layer ancestry and direct staging shortcut`() {
            // Real shape: a mart with deep ancestry where at least one staging
            // model bypasses the intermediate layer. Symmetric of the "utility
            // fan-out" pattern but exercised via upHops walk.
            //
            //   src_app__users → stg_users  ─┐
            //   src_app__orders → stg_orders ┼─→ int_user_combined → mart_user
            //   stg_date_series ─────────────┘                       ↑
            //   stg_date_series ─────────────────────────────────────┘  (direct, skip)
            val manifest = parseManifest(
                """
                {
                  "nodes": {
                    "model.demo.stg_users":          ${node("stg_users", layerPath = "staging/stg_users.sql")},
                    "model.demo.stg_orders":         ${node("stg_orders", layerPath = "staging/stg_orders.sql")},
                    "model.demo.stg_date_series":    ${node("stg_date_series", layerPath = "staging/stg_date_series.sql")},
                    "model.demo.int_user_combined":  ${node("int_user_combined", layerPath = "intermediate/int_user_combined.sql")},
                    "model.demo.mart_user":          ${node("mart_user", layerPath = "marts/mart_user.sql")}
                  },
                  "sources": {
                    "source.demo.app.users":  ${source("users")},
                    "source.demo.app.orders": ${source("orders")}
                  },
                  "child_map": {
                    "source.demo.app.users":         ["model.demo.stg_users"],
                    "source.demo.app.orders":        ["model.demo.stg_orders"],
                    "model.demo.stg_users":          ["model.demo.int_user_combined"],
                    "model.demo.stg_orders":         ["model.demo.int_user_combined"],
                    "model.demo.stg_date_series":    ["model.demo.int_user_combined", "model.demo.mart_user"],
                    "model.demo.int_user_combined":  ["model.demo.mart_user"],
                    "model.demo.mart_user":          []
                  },
                  "parent_map": {
                    "source.demo.app.users":         [],
                    "source.demo.app.orders":        [],
                    "model.demo.stg_users":          ["source.demo.app.users"],
                    "model.demo.stg_orders":         ["source.demo.app.orders"],
                    "model.demo.stg_date_series":    [],
                    "model.demo.int_user_combined":  ["model.demo.stg_users", "model.demo.stg_orders", "model.demo.stg_date_series"],
                    "model.demo.mart_user":          ["model.demo.int_user_combined", "model.demo.stg_date_series"]
                  }
                }
                """.trimIndent(),
            )
            val payload = manifest.buildLineage(
                seed = "model.demo.mart_user",
                upHops = 3,
                downHops = 0,
            )
            val ids = payload.models.map { it.uniqueId }.toSet()
            assertEquals(
                setOf(
                    "model.demo.mart_user",
                    "model.demo.int_user_combined",
                    "model.demo.stg_users",
                    "model.demo.stg_orders",
                    "model.demo.stg_date_series",
                    "source.demo.app.users",
                    "source.demo.app.orders",
                ),
                ids,
                "upHops=3 must reach all 4 layers (mart, int, staging, source) including the shortcut-ancestor stg_date_series",
            )
            // Verify the skip edge stg_date_series → mart_user is in the output.
            val edgePairs = payload.modelEdges.map { it.sourceUniqueId to it.targetUniqueId }.toSet()
            assertTrue(
                "model.demo.stg_date_series" to "model.demo.mart_user" in edgePairs,
                "stg → mart skip edge must survive (it's how the user spots redundant refs in their dbt code)",
            )
        }

        @Test
        fun `multi-level shortcuts at different depths obey hop budget independently`() {
            // Two shortcuts of different lengths from the same seed — exercises
            // that hop budget interacts correctly with skip-edge re-entry.
            //
            //   A → B → C → D → E
            //   A → C (skip 1, depth-1 shortcut)
            //   A → E (skip 3, depth-1 shortcut to a deep node)
            val manifest = parseManifest(
                """
                {
                  "nodes": {
                    "model.demo.a": ${node("a", layerPath = "staging/a.sql")},
                    "model.demo.b": ${node("b", layerPath = "intermediate/b.sql")},
                    "model.demo.c": ${node("c", layerPath = "intermediate/c.sql")},
                    "model.demo.d": ${node("d", layerPath = "intermediate/d.sql")},
                    "model.demo.e": ${node("e", layerPath = "marts/e.sql")}
                  },
                  "sources": {},
                  "child_map": {
                    "model.demo.a": ["model.demo.b", "model.demo.c", "model.demo.e"],
                    "model.demo.b": ["model.demo.c"],
                    "model.demo.c": ["model.demo.d"],
                    "model.demo.d": ["model.demo.e"],
                    "model.demo.e": []
                  },
                  "parent_map": {
                    "model.demo.a": [],
                    "model.demo.b": ["model.demo.a"],
                    "model.demo.c": ["model.demo.a", "model.demo.b"],
                    "model.demo.d": ["model.demo.c"],
                    "model.demo.e": ["model.demo.a", "model.demo.d"]
                  }
                }
                """.trimIndent(),
            )

            // downHops=1: only direct children reachable. D needs >=1 hop AFTER C, so out.
            val tightBudget = manifest.buildLineage(seed = "model.demo.a", upHops = 0, downHops = 1)
            val tightIds = tightBudget.models.map { it.uniqueId }.toSet()
            assertEquals(
                setOf("model.demo.a", "model.demo.b", "model.demo.c", "model.demo.e"),
                tightIds,
                "downHops=1 reaches direct children only — D is 2-hop minimum, must be excluded",
            )

            // downHops=2: D becomes reachable via the A→C skip + C→D edge.
            val mediumBudget = manifest.buildLineage(seed = "model.demo.a", upHops = 0, downHops = 2)
            val mediumIds = mediumBudget.models.map { it.uniqueId }.toSet()
            assertEquals(
                setOf("model.demo.a", "model.demo.b", "model.demo.c", "model.demo.d", "model.demo.e"),
                mediumIds,
                "downHops=2 unlocks D via the A→C shortcut → C→D path",
            )
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
        fun `marts-prefix subdirectories all map to marts (regression)`() {
            // dbt projects often namespace marts (e.g. iCHEF's `marts_msd/`,
            // `marts_qlr/`). All such mart_* / marts_* prefixes share the
            // "marts" color tier instead of being passed through verbatim.
            // The regression: passing through "marts_msd" verbatim made the
            // frontend crash on `theme.layers["marts_msd"].bg` undefined.
            assertEquals("marts", layerFor("marts_msd/orders.sql"))
            assertEquals("marts", layerFor("marts_qlr/orders.sql"))
            assertEquals("marts", layerFor("mart_team_a/orders.sql"))
        }

        @Test
        fun `unknown first segment returns null (frontend defaults to staging)`() {
            // Returning the raw segment broke the frontend (which expects only
            // four canonical layer names). null lets the frontend's
            // normalizeLayer fall back to "staging" cleanly.
            assertNull(layerFor("custom/orders.sql"))
            assertNull(layerFor("dash/orders.sql"))
            assertNull(layerFor("ads_data/orders.sql"))
        }

        @Test
        fun `model with no path field returns null layer and folder`() {
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
            val model = manifest.buildLineage(seed = "model.demo.orders").models.single()
            assertNull(model.layer)
            assertNull(model.folder)
        }

        @Test
        fun `folder preserves the raw first path segment verbatim`() {
            // The frontend renders this on the model card chip, so namespaced
            // dbt project layouts (`marts_msd/`, `dash/`, etc.) keep their
            // distinction even when `layer` collapses to a canonical bucket.
            assertEquals("marts_msd" to "marts", folderAndLayerFor("marts_msd/orders.sql"))
            assertEquals("marts_qlr" to "marts", folderAndLayerFor("marts_qlr/orders.sql"))
            assertEquals("staging" to "staging", folderAndLayerFor("staging/stg_orders.sql"))
            assertEquals("dash" to null, folderAndLayerFor("dash/orders.sql"))
        }

        @Test
        fun `folder preserves original case (chip rendering uses display name)`() {
            // inferLayer lowercases for canonical-bucket matching, but folder
            // keeps the original path so the chip respects the user's casing.
            assertEquals("Marts_MSD", folderAndLayerFor("Marts_MSD/orders.sql").first)
        }

        @Test
        fun `source models get folder=source so chip is non-empty`() {
            // Sources have no path/folder structure on disk; we still want the
            // chip to render something rather than a blank pill.
            val manifest = parseManifest(
                """
                {
                  "nodes": {},
                  "sources": {
                    "source.demo.app.users": ${source("users")}
                  },
                  "child_map": {},
                  "parent_map": {}
                }
                """.trimIndent(),
            )
            val model = manifest.buildLineage(seed = null).models.single()
            assertEquals("source", model.layer)
            assertEquals("source", model.folder)
        }

        private fun layerFor(path: String): String? = folderAndLayerFor(path).second

        private fun folderAndLayerFor(path: String): Pair<String?, String?> {
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
            val model = manifest.buildLineage(seed = "model.demo.x").models.single()
            return model.folder to model.layer
        }
    }

    @Nested
    inner class MaterializationTests {

        @Test
        fun `table materialization is extracted from config`() {
            assertEquals("table", materializationFor("""{"materialized": "table"}"""))
        }

        @Test
        fun `view materialization is extracted from config`() {
            assertEquals("view", materializationFor("""{"materialized": "view"}"""))
        }

        @Test
        fun `incremental materialization is extracted from config`() {
            assertEquals("incremental", materializationFor("""{"materialized": "incremental"}"""))
        }

        @Test
        fun `unknown materialization values pass through verbatim`() {
            // We don't enumerate dbt's full materialization vocabulary in
            // Kotlin; the frontend's letter-badge map decides which strings
            // to render. Strings like "ephemeral" / "materialized_view" /
            // some custom adapter materialization must reach the React side
            // unchanged.
            assertEquals("materialized_view", materializationFor("""{"materialized": "materialized_view"}"""))
            assertEquals("ephemeral", materializationFor("""{"materialized": "ephemeral"}"""))
        }

        @Test
        fun `model with no config object yields null materialization`() {
            // Defensive: older / partial manifests may lack config entirely.
            // The frontend treats null as "skip the badge" rather than a
            // mystery dash, so this test locks the null-vs-empty contract.
            assertNull(materializationFor(configJson = null))
        }

        @Test
        fun `config without materialized key yields null materialization`() {
            // dbt always emits `materialized` for models, but we still want
            // to be defensive in case a future/older schema differs.
            assertNull(materializationFor("""{"some_other_key": "value"}"""))
        }

        @Test
        fun `config that is a string instead of object yields null (no crash)`() {
            // Pathological — we don't expect this from real dbt, but Gson
            // would throw ClassCastException on .asJsonObject. The extractor
            // must defend against that.
            assertNull(materializationFor("\"oops_string\""))
        }

        @Test
        fun `sources never get a materialization regardless of payload shape`() {
            // Sources are warehouse inputs; rendering "T"/"V"/"I" on a
            // source card would be misleading. Locked to null.
            val manifest = parseManifest(
                """
                {
                  "nodes": {},
                  "sources": {
                    "source.demo.app.users": ${source("users")}
                  },
                  "child_map": {},
                  "parent_map": {}
                }
                """.trimIndent(),
            )
            val src = manifest.buildLineage(seed = null).models.single { it.uniqueId.startsWith("source.") }
            assertNull(src.materialization, "sources must always have null materialization")
        }

        /**
         * Build a one-model manifest where the model node optionally carries
         * a `config` field with [configJson] verbatim, then return the
         * extracted materialization value (or null).
         */
        private fun materializationFor(configJson: String?): String? {
            val configField = if (configJson != null) ""","config": $configJson""" else ""
            val manifest = parseManifest(
                """
                {
                  "nodes": {
                    "model.demo.x": {
                      "resource_type": "model",
                      "name": "x",
                      "package_name": "demo",
                      "path": "marts/x.sql",
                      "original_file_path": "models/marts/x.sql"
                      $configField
                    }
                  },
                  "sources": {},
                  "child_map": {},
                  "parent_map": {}
                }
                """.trimIndent(),
            )
            return manifest.buildLineage(seed = "model.demo.x").models.single().materialization
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
