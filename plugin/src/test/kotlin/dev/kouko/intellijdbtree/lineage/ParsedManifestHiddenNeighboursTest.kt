package dev.kouko.intellijdbtree.lineage

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertEquals

/**
 * Verifies the "hidden neighbour" annotations [ParsedManifest.buildLineage]
 * writes onto every result [DbtModel].
 *
 * The private [ParsedManifest.countHiddenNeighbours] is exercised indirectly:
 * we build small in-memory manifests with hand-crafted parent_map / child_map
 * topologies, run buildLineage with various hop budgets, and assert the
 * `hiddenUpstream` / `hiddenDownstream` counts on the returned DbtModels.
 *
 * Contract under test:
 *  - Counts only neighbours that ARE models or sources.
 *  - Tests / snapshots / seeds in parent_map or child_map are ignored.
 *  - A neighbour that's already in the visible payload doesn't count.
 */
class ParsedManifestHiddenNeighboursTest {

    @Nested
    inner class HopBudgetTests {

        // source.app.app_orders → stg → int → mart
        // (the same kind of vertical chain ParsedManifestTest.linearChain
        // uses, kept local so this file is self-contained.)
        private val verticalChain = parseManifest(
            """
            {
              "nodes": {
                "model.demo.stg":  ${node("stg",  layerPath = "staging/stg.sql")},
                "model.demo.int":  ${node("int",  layerPath = "intermediate/int.sql")},
                "model.demo.mart": ${node("mart", layerPath = "marts/mart.sql")}
              },
              "sources": {
                "source.demo.app.app_orders": ${source("app_orders")}
              },
              "child_map": {
                "source.demo.app.app_orders": ["model.demo.stg"],
                "model.demo.stg":             ["model.demo.int"],
                "model.demo.int":             ["model.demo.mart"],
                "model.demo.mart":            []
              },
              "parent_map": {
                "source.demo.app.app_orders": [],
                "model.demo.stg":             ["source.demo.app.app_orders"],
                "model.demo.int":             ["model.demo.stg"],
                "model.demo.mart":            ["model.demo.int"]
              }
            }
            """.trimIndent(),
        )

        @Test
        fun `tight hop budget hides both neighbours and sets +1 on each side`() {
            // Seed in the middle of the chain with hops=0 → only the seed
            // is visible; the source above and the int below should both
            // count as hidden neighbours.
            val payload = verticalChain.buildLineage(
                seed = "model.demo.stg",
                upHops = 0,
                downHops = 0,
            )
            val stg = payload.models.single { it.uniqueId == "model.demo.stg" }
            assertEquals(1, stg.hiddenUpstream, "source.app_orders is upstream but cropped by hops")
            assertEquals(1, stg.hiddenDownstream, "model.demo.int is downstream but cropped by hops")
        }

        @Test
        fun `unlimited hops make hidden counts zero on every model`() {
            val payload = verticalChain.buildLineage(
                seed = "model.demo.stg",
                upHops = Int.MAX_VALUE,
                downHops = Int.MAX_VALUE,
            )
            // All 4 nodes (source + stg + int + mart) should be visible.
            assertEquals(4, payload.models.size)
            val stg = payload.models.single { it.uniqueId == "model.demo.stg" }
            assertEquals(0, stg.hiddenUpstream, "source.app_orders is in payload — not hidden")
            assertEquals(0, stg.hiddenDownstream, "int is in payload — not hidden")
            // And the chain endpoints should also report 0 / 0.
            val source = payload.models.single { it.uniqueId == "source.demo.app.app_orders" }
            assertEquals(0, source.hiddenUpstream)
            assertEquals(0, source.hiddenDownstream)
            val mart = payload.models.single { it.uniqueId == "model.demo.mart" }
            assertEquals(0, mart.hiddenUpstream)
            assertEquals(0, mart.hiddenDownstream)
        }
    }

    @Nested
    inner class NeighbourTypeFilterTests {

        @Test
        fun `sources count toward hiddenUpstream when cropped`() {
            // mart has 2 source parents in the manifest. With upHops=0,
            // both fall outside the visible sphere and must count.
            val manifest = parseManifest(
                """
                {
                  "nodes": {
                    "model.demo.mart": ${node("mart", layerPath = "marts/mart.sql")}
                  },
                  "sources": {
                    "source.demo.app.users":  ${source("users")},
                    "source.demo.app.orders": ${source("orders")}
                  },
                  "child_map": {
                    "source.demo.app.users":  ["model.demo.mart"],
                    "source.demo.app.orders": ["model.demo.mart"],
                    "model.demo.mart":        []
                  },
                  "parent_map": {
                    "source.demo.app.users":  [],
                    "source.demo.app.orders": [],
                    "model.demo.mart":        ["source.demo.app.users", "source.demo.app.orders"]
                  }
                }
                """.trimIndent(),
            )

            val payload = manifest.buildLineage(
                seed = "model.demo.mart",
                upHops = 0,
                downHops = 0,
            )
            val mart = payload.models.single { it.uniqueId == "model.demo.mart" }
            assertEquals(2, mart.hiddenUpstream, "both sources are upstream and cropped")
            assertEquals(0, mart.hiddenDownstream)
        }

        @Test
        fun `tests, snapshots, and seeds in the graph do not count as hidden neighbours`() {
            // mart has:
            //   - 1 model parent (visible-when-counted-as-hidden under upHops=0)
            //   - 1 test child + 1 snapshot child + 1 seed child (must NOT count)
            val manifest = parseManifest(
                """
                {
                  "nodes": {
                    "model.demo.mart":               ${node("mart", layerPath = "marts/mart.sql")},
                    "model.demo.parent_model":       ${node("parent_model", layerPath = "intermediate/parent_model.sql")},
                    "test.demo.unique_mart_id":      ${node("unique_mart_id",  resourceType = "test")},
                    "snapshot.demo.mart_snapshot":   ${node("mart_snapshot",   resourceType = "snapshot")},
                    "seed.demo.country_codes":       ${node("country_codes",   resourceType = "seed")}
                  },
                  "sources": {},
                  "child_map": {
                    "model.demo.parent_model": ["model.demo.mart"],
                    "model.demo.mart": [
                      "test.demo.unique_mart_id",
                      "snapshot.demo.mart_snapshot",
                      "seed.demo.country_codes"
                    ]
                  },
                  "parent_map": {
                    "model.demo.parent_model": [],
                    "model.demo.mart":             ["model.demo.parent_model"],
                    "test.demo.unique_mart_id":    ["model.demo.mart"],
                    "snapshot.demo.mart_snapshot": ["model.demo.mart"],
                    "seed.demo.country_codes":     []
                  }
                }
                """.trimIndent(),
            )

            val payload = manifest.buildLineage(
                seed = "model.demo.mart",
                upHops = 0,
                downHops = 0,
            )
            val mart = payload.models.single { it.uniqueId == "model.demo.mart" }
            assertEquals(
                1,
                mart.hiddenUpstream,
                "the model parent is the only countable hidden upstream neighbour",
            )
            assertEquals(
                0,
                mart.hiddenDownstream,
                "tests, snapshots, and seeds are excluded from the hidden chip count",
            )
        }

        @Test
        fun `multi-parent model counts each cropped parent and reports zero when all visible`() {
            // mart declares 3 distinct model parents (p_a, p_b, p_c).
            // Two passes:
            //   1. upHops=0 crops all 3 → hiddenUpstream == 3
            //   2. upHops=1 brings all 3 into the payload → hiddenUpstream == 0
            // Each parent's only declared child is mart, so once mart is in
            // the payload they should each report hiddenDownstream == 0.
            val manifest = parseManifest(
                """
                {
                  "nodes": {
                    "model.demo.mart": ${node("mart", layerPath = "marts/mart.sql")},
                    "model.demo.p_a":  ${node("p_a",  layerPath = "intermediate/p_a.sql")},
                    "model.demo.p_b":  ${node("p_b",  layerPath = "intermediate/p_b.sql")},
                    "model.demo.p_c":  ${node("p_c",  layerPath = "intermediate/p_c.sql")}
                  },
                  "sources": {},
                  "child_map": {
                    "model.demo.p_a":  ["model.demo.mart"],
                    "model.demo.p_b":  ["model.demo.mart"],
                    "model.demo.p_c":  ["model.demo.mart"],
                    "model.demo.mart": []
                  },
                  "parent_map": {
                    "model.demo.p_a":  [],
                    "model.demo.p_b":  [],
                    "model.demo.p_c":  [],
                    "model.demo.mart": ["model.demo.p_a", "model.demo.p_b", "model.demo.p_c"]
                  }
                }
                """.trimIndent(),
            )

            // Case 1: upHops=0 crops everything upstream → 3 hidden.
            val cropped = manifest.buildLineage(
                seed = "model.demo.mart",
                upHops = 0,
                downHops = 0,
            )
            val martCropped = cropped.models.single { it.uniqueId == "model.demo.mart" }
            assertEquals(3, martCropped.hiddenUpstream, "all 3 parents are cropped by upHops=0")

            // Case 2: upHops=1 brings all 3 parents into the visible payload
            // → 0 hidden upstream on mart, and each parent reports 0 hidden
            // downstream because mart is the only declared child.
            val visible = manifest.buildLineage(
                seed = "model.demo.mart",
                upHops = 1,
                downHops = 0,
            )
            val martVisible = visible.models.single { it.uniqueId == "model.demo.mart" }
            assertEquals(0, martVisible.hiddenUpstream, "all 3 parents now in payload — none hidden")
            visible.models
                .filter { it.uniqueId.startsWith("model.demo.p_") }
                .forEach { p ->
                    assertEquals(
                        0,
                        p.hiddenDownstream,
                        "parent ${p.uniqueId}'s only child (mart) is in the payload",
                    )
                }
        }
    }

    // ---- helpers (mirrored from ParsedManifestTest) ---------------------

    private fun parseManifest(
        manifestJson: String,
        catalogJson: String? = null,
    ): ParsedManifest {
        val manifest = JsonParser.parseString(manifestJson).asJsonObject
        val catalog: JsonObject? = catalogJson?.let { JsonParser.parseString(it).asJsonObject }
        return ParsedManifest(manifest, catalog, Path.of("/dbt"))
    }

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
