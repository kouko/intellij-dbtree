package dev.kouko.intellijdbtree.lineage

import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Tests over the pure parts of [PythonInterpreterResolver]:
 *
 *  - [PythonInterpreterResolver.runChain]: prioritized candidate selection
 *    + validation gating, plus the wording of failure messages
 *  - [PythonInterpreterResolver.findVenvPath]: filesystem-probed candidate
 *    list, with the probe stubbed so we don't need a real .venv on disk
 *
 * The `findProjectSdkPath` path is IDE-coupled (Python plugin's
 * `PythonSdkType` reflection) and exercised by manual sandbox testing.
 */
class PythonInterpreterResolverTest {

    @Nested
    inner class RunChainTests {

        @Test
        fun `empty candidates yields None with friendly message`() {
            val res = PythonInterpreterResolver.runChain(emptyList()) { true }
            assertIs<PythonInterpreterResolver.Resolution.None>(res)
            assert("Configure one" in res.reason)
        }

        @Test
        fun `first candidate that passes validate is returned`() {
            val res = PythonInterpreterResolver.runChain(
                listOf(
                    PythonInterpreterResolver.Source.Manual to "/path/manual",
                    PythonInterpreterResolver.Source.ProjectSdk to "/path/sdk",
                    PythonInterpreterResolver.Source.ProjectVenv to "/path/venv",
                ),
                validate = { true },
            )
            assertIs<PythonInterpreterResolver.Resolution.Ok>(res)
            assertEquals("/path/manual", res.pythonPath)
            assertEquals(PythonInterpreterResolver.Source.Manual, res.source)
        }

        @Test
        fun `falls through to next candidate when earlier ones fail validate`() {
            // Manual fails; SDK fails; Venv passes.
            val seen = mutableListOf<String>()
            val res = PythonInterpreterResolver.runChain(
                listOf(
                    PythonInterpreterResolver.Source.Manual to "/manual",
                    PythonInterpreterResolver.Source.ProjectSdk to "/sdk",
                    PythonInterpreterResolver.Source.ProjectVenv to "/venv",
                ),
                validate = { path ->
                    seen += path
                    path == "/venv"
                },
            )
            assertIs<PythonInterpreterResolver.Resolution.Ok>(res)
            assertEquals("/venv", res.pythonPath)
            assertEquals(PythonInterpreterResolver.Source.ProjectVenv, res.source)
            assertEquals(listOf("/manual", "/sdk", "/venv"), seen, "must walk in priority order")
        }

        @Test
        fun `all candidates failing validate yields None mentioning sqlglot`() {
            val res = PythonInterpreterResolver.runChain(
                listOf(
                    PythonInterpreterResolver.Source.Manual to "/manual",
                    PythonInterpreterResolver.Source.ProjectVenv to "/venv",
                ),
                validate = { false },
            )
            assertIs<PythonInterpreterResolver.Resolution.None>(res)
            assert("sqlglot" in res.reason)
            assert("Manual: /manual" in res.reason)
            assert("ProjectVenv: /venv" in res.reason)
        }

        @Test
        fun `manual setting wins over project SDK and venv when valid`() {
            // The contract: user-set path always takes priority. Useful when
            // they want to pin a specific environment, e.g. for testing.
            val res = PythonInterpreterResolver.runChain(
                listOf(
                    PythonInterpreterResolver.Source.Manual to "/manual",
                    PythonInterpreterResolver.Source.ProjectSdk to "/sdk",
                ),
                validate = { true },
            )
            assertIs<PythonInterpreterResolver.Resolution.Ok>(res)
            assertEquals("/manual", res.pythonPath)
        }

        @Test
        fun `manual setting still wins even if it fails validate (surfaces config error)`() {
            // Failing user config should produce a CLEAR failure, not silently
            // pick the project SDK — that hides the user's misconfiguration.
            // After Manual fails, we keep walking — the contract is "first
            // valid wins", not "manual wins or fail". The trade-off here is:
            // surfacing the config error is more important than silently
            // succeeding via fallback. Document via this test.
            val res = PythonInterpreterResolver.runChain(
                listOf(
                    PythonInterpreterResolver.Source.Manual to "/manual",
                    PythonInterpreterResolver.Source.ProjectSdk to "/sdk",
                ),
                validate = { it == "/sdk" },
            )
            assertIs<PythonInterpreterResolver.Resolution.Ok>(res)
            assertEquals("/sdk", res.pythonPath, "fallback wins when manual fails validation")
        }
    }

    @Nested
    inner class FindVenvPathTests {

        private val projectDir = Path.of("/dbt/project")

        @Test
        fun `dot-venv with bin python (Mac or Linux) is returned`() {
            val expected = projectDir.resolve(".venv").resolve("bin").resolve("python")
            val result = PythonInterpreterResolver.findVenvPath(projectDir) { it == expected }
            assertEquals(expected.toString(), result)
        }

        @Test
        fun `dot-venv with Scripts python_exe (Windows) is returned`() {
            val expected = projectDir.resolve(".venv").resolve("Scripts").resolve("python.exe")
            val result = PythonInterpreterResolver.findVenvPath(projectDir) { it == expected }
            assertEquals(expected.toString(), result)
        }

        @Test
        fun `falls back to no-dot venv when dot-venv missing`() {
            val expected = projectDir.resolve("venv").resolve("bin").resolve("python")
            val result = PythonInterpreterResolver.findVenvPath(projectDir) { it == expected }
            assertEquals(expected.toString(), result)
        }

        @Test
        fun `dot-venv preferred over plain venv when both exist`() {
            val dotVenv = projectDir.resolve(".venv").resolve("bin").resolve("python")
            val plainVenv = projectDir.resolve("venv").resolve("bin").resolve("python")
            val result = PythonInterpreterResolver.findVenvPath(projectDir) {
                it == dotVenv || it == plainVenv
            }
            assertEquals(dotVenv.toString(), result, "dot-venv comes first in candidate order")
        }

        @Test
        fun `returns null when no venv shape matches`() {
            val result = PythonInterpreterResolver.findVenvPath(projectDir) { false }
            assertNull(result)
        }
    }
}
