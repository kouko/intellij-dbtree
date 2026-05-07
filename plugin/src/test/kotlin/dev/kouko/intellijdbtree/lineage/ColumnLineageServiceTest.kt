package dev.kouko.intellijdbtree.lineage

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pure-function tests over [describeSidecarFailure] — the user-facing
 * message generator for sidecar failures. The full subprocess flow needs
 * IntelliJ's CapturingProcessHandler and is exercised by manual / sandbox
 * testing; here we lock the wording contract: each failure mode must
 * surface in the toolbar with enough context to act on.
 */
class ColumnLineageServiceTest {

    @Nested
    inner class DescribeSidecarFailureTests {

        @Test
        fun `success returns null — caller publishes a real result`() {
            val msg = describeSidecarFailure(
                isTimeout = false,
                exitCode = 0,
                stderr = "",
                label = "model.demo.orders.id",
                timeoutSeconds = 15,
            )
            assertNull(msg)
        }

        @Test
        fun `timeout names the limit AND points at the settings page`() {
            // The #1 large-project failure mode. We MUST tell the user
            // where to raise the limit, not just that it timed out — otherwise
            // they're stuck without recourse.
            val msg = describeSidecarFailure(
                isTimeout = true,
                exitCode = -1,
                stderr = "",
                label = "model.iCHEF_dbt_pipline.mart_user.user_id",
                timeoutSeconds = 15,
            )
            assertNotNull(msg)
            assertTrue("15s" in msg, "timeout duration must appear so user knows the budget hit")
            assertTrue("Settings" in msg && "dbtree" in msg, "must point at Settings → Tools → dbtree")
            assertTrue("idea.log" in msg, "must mention idea.log for partial output")
        }

        @Test
        fun `non-zero exit quotes stderr tail in the toolbar`() {
            // Don't make the user open idea.log just to see what blew up.
            // The toolbar should show the gist of the Python traceback.
            val stderr = "Traceback (most recent call last):\n" +
                "  File \"cli.py\", line 42, in <module>\n" +
                "ImportError: No module named sqlglot.optimizer.unnest_subqueries\n"
            val msg = describeSidecarFailure(
                isTimeout = false,
                exitCode = 1,
                stderr = stderr,
                label = "model.demo.orders.id",
                timeoutSeconds = 15,
            )
            assertNotNull(msg)
            assertTrue("exit" in msg && "1" in msg, "exit code must appear: $msg")
            assertTrue("ImportError" in msg, "stderr tail must be quoted so user can self-diagnose: $msg")
        }

        @Test
        fun `non-zero exit with empty stderr falls back to a placeholder`() {
            // A crashed process can leave stderr empty. The toolbar message
            // should still make sense — don't show a dangling "stderr: ".
            val msg = describeSidecarFailure(
                isTimeout = false,
                exitCode = 137,  // OOM kill on Linux
                stderr = "",
                label = "model.demo.orders.id",
                timeoutSeconds = 15,
            )
            assertNotNull(msg)
            assertTrue("137" in msg)
            assertTrue("(no stderr)" in msg, "blank stderr must show a placeholder, not 'stderr: '")
        }

        @Test
        fun `stderr longer than 200 chars is truncated from the start (keeps the tail)`() {
            // Python tracebacks have the actual error at the BOTTOM. Truncating
            // from the head (keeping the tail) preserves the most useful info.
            val noise = "noise line that should be dropped\n".repeat(20)
            val tail = "RuntimeError: deeply nested column not resolvable"
            val msg = describeSidecarFailure(
                isTimeout = false,
                exitCode = 1,
                stderr = noise + tail,
                label = "model.demo.x.y",
                timeoutSeconds = 15,
            )
            assertNotNull(msg)
            assertTrue(tail in msg, "tail of stderr must survive truncation — that's where the actual error is")
        }
    }

    @Nested
    inner class ProcessSidecarOutputTests {

        // Lenient JSON to match the production sidecarJson; tests should
        // accept the same payload shape the real service does.
        private val testJson = Json { ignoreUnknownKeys = true }

        @Test
        fun `valid stdout deserializes to Ok`() {
            val outcome = processSidecarOutput(
                isTimeout = false,
                exitCode = 0,
                stderr = "",
                stdout = """{"columns": ["id", "name"], "error": null}""",
                label = "model.demo.x (list-columns)",
                timeoutSeconds = 15,
                deserializer = ListColumnsResult.serializer(),
                json = testJson,
            )
            val ok = assertIs<SidecarOutcome.Ok<ListColumnsResult>>(outcome)
            assertEquals(listOf("id", "name"), ok.value.columns)
        }

        @Test
        fun `timeout returns Failed with the timeout message — does NOT attempt to parse stdout`() {
            // Even if stdout happens to contain valid JSON (partial output
            // before the timeout fired), the timeout takes priority — running
            // a partial trace as if it were complete would silently mislead
            // the user.
            val outcome = processSidecarOutput(
                isTimeout = true,
                exitCode = -1,
                stderr = "",
                stdout = """{"columns": ["partial"]}""",
                label = "model.demo.x.y",
                timeoutSeconds = 30,
                deserializer = ListColumnsResult.serializer(),
                json = testJson,
            )
            val failed = assertIs<SidecarOutcome.Failed>(outcome)
            assertTrue("timed out after 30s" in failed.message, "must include configured timeout: ${failed.message}")
        }

        @Test
        fun `non-zero exit returns Failed regardless of stdout`() {
            val outcome = processSidecarOutput(
                isTimeout = false,
                exitCode = 2,
                stderr = "ImportError: sqlglot not installed",
                stdout = "",
                label = "model.demo.x.y",
                timeoutSeconds = 15,
                deserializer = ListColumnsResult.serializer(),
                json = testJson,
            )
            val failed = assertIs<SidecarOutcome.Failed>(outcome)
            assertTrue("exit" in failed.message && "2" in failed.message)
            assertTrue("ImportError" in failed.message, "stderr must surface in the message")
        }

        @Test
        fun `bad JSON on a successful exit code returns Failed with invalid-JSON wording`() {
            // exitCode == 0, but stdout isn't parseable. Must classify as a
            // distinct failure — "the sidecar lied" — not silently lose data.
            val outcome = processSidecarOutput(
                isTimeout = false,
                exitCode = 0,
                stderr = "",
                stdout = "this is not json",
                label = "model.demo.x (list-columns)",
                timeoutSeconds = 15,
                deserializer = ListColumnsResult.serializer(),
                json = testJson,
            )
            val failed = assertIs<SidecarOutcome.Failed>(outcome)
            assertTrue(
                "invalid JSON" in failed.message,
                "must classify as 'invalid JSON' so user can grep idea.log: ${failed.message}",
            )
            assertTrue("model.demo.x (list-columns)" in failed.message, "label must appear")
        }
    }

    @Nested
    inner class ParseColumnListTests {

        @Test
        fun `bare star is dropped — sqlglot 'SELECT *' placeholder must not render`() {
            assertEquals(emptyList(), parseColumnList(listOf("*")))
        }

        @Test
        fun `mixed list keeps real columns and drops only the star`() {
            assertEquals(listOf("id", "name"), parseColumnList(listOf("id", "*", "name")))
        }

        @Test
        fun `regular column list passes through unchanged`() {
            val cols = listOf("id", "user_id", "created_at")
            assertEquals(cols, parseColumnList(cols))
        }

        @Test
        fun `empty list stays empty`() {
            assertEquals(emptyList(), parseColumnList(emptyList()))
        }
    }

    @Nested
    inner class GetOrComputeTests {

        @Test
        fun `first call computes and caches`() {
            val cache = ConcurrentHashMap<String, List<String>>()
            val invocations = AtomicInteger(0)
            val result = cache.getOrCompute("k") {
                invocations.incrementAndGet()
                listOf("a", "b")
            }
            assertEquals(listOf("a", "b"), result)
            assertEquals(1, invocations.get())
            assertEquals(listOf("a", "b"), cache["k"], "cache must contain the computed value")
        }

        @Test
        fun `second call returns cached value without invoking compute`() {
            val cache = ConcurrentHashMap<String, List<String>>()
            cache["k"] = listOf("cached")
            val result = cache.getOrCompute("k") {
                error("compute must NOT be called when cache is hit")
            }
            assertEquals(listOf("cached"), result)
        }

        @Test
        fun `compute returning null does NOT poison the cache`() {
            // The whole point of using a custom helper instead of
            // computeIfAbsent: a failing sidecar (returning null) must let
            // the next call retry — not lock in a permanent null.
            val cache = ConcurrentHashMap<String, List<String>>()
            val invocations = AtomicInteger(0)

            val first = cache.getOrCompute("k") {
                invocations.incrementAndGet()
                null
            }
            assertNull(first)
            assertTrue(cache.isEmpty(), "null result must NOT be cached")

            // Second call with a successful compute — should run, not return cached null.
            val second = cache.getOrCompute("k") {
                invocations.incrementAndGet()
                listOf("retry succeeded")
            }
            assertEquals(listOf("retry succeeded"), second)
            assertEquals(2, invocations.get(), "compute must run again after a null return")
        }

        @Test
        fun `concurrent winners share the same cached instance via putIfAbsent`() {
            // Race window: thread A reads null, computes valueA, calls putIfAbsent.
            // Thread B reads null, computes valueB, calls putIfAbsent — A's already
            // there so B gets A's value back. Both threads return THE SAME instance.
            // We can't reliably trigger the race in a deterministic test, so we
            // simulate by pre-populating the cache between the get-null check and
            // the putIfAbsent. The invariant we lock: putIfAbsent return is honored.
            val cache = ConcurrentHashMap<String, List<String>>()
            val winning = listOf("winner")
            val result = cache.getOrCompute("k") {
                // Simulate another thread winning during compute.
                cache.putIfAbsent("k", winning)
                listOf("loser")
            }
            assertSame(winning, result, "race winner must be returned, even if our compute also produced a value")
            assertSame(winning, cache["k"], "cache must hold the race winner, not our late value")
        }
    }
}
