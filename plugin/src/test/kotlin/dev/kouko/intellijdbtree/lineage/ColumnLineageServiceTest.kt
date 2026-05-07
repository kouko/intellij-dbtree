package dev.kouko.intellijdbtree.lineage

import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
}
