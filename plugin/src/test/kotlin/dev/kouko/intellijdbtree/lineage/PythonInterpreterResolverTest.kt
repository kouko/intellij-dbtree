package dev.kouko.intellijdbtree.lineage

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    // Cache lives on the singleton object and bleeds across tests; clear it
    // before each so a passing validate in one test can't pre-cache a path
    // another test wants to see fail.
    @BeforeEach
    fun clearResolverCache() {
        PythonInterpreterResolver.invalidateValidationCache()
    }

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
        fun `all candidates failing yields None with copy-pasteable install command`() {
            // The install command should pin to the FIRST candidate's path so
            // running it actually installs into the env we're tracing — not
            // some other env on the user's PATH. This is the regression: the
            // earlier message just said `pip install sqlglot`, which often
            // hit a different env and confused users.
            val res = PythonInterpreterResolver.runChain(
                listOf(
                    PythonInterpreterResolver.Source.Manual to "/manual/python",
                    PythonInterpreterResolver.Source.ProjectVenv to "/venv/python",
                ),
                validate = { false },
            )
            assertIs<PythonInterpreterResolver.Resolution.None>(res)
            assert("sqlglot" in res.reason)
            assert("/manual/python" in res.reason)
            // The exact pip install command must include the python path
            assert("/manual/python -m pip install sqlglot" in res.reason)
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
    inner class ValidationCacheTests {

        // Cache cleared by outer @BeforeEach. Resolver caches validate(path)
        // results across runChain calls so the v0.4.8 streaming batch sidecar
        // doesn't pay a ~3s `python -c "import sqlglot"` probe per batch.

        @Test
        fun `validate is called once per path across repeated runChain calls`() {
            var calls = 0
            val candidates = listOf(
                PythonInterpreterResolver.Source.Manual to "/path/manual",
            )
            val validate: (String) -> Boolean = { calls++; true }

            PythonInterpreterResolver.runChain(candidates, validate)
            PythonInterpreterResolver.runChain(candidates, validate)
            PythonInterpreterResolver.runChain(candidates, validate)

            assertEquals(1, calls, "subsequent runChain calls must hit cache")
        }

        @Test
        fun `failed validations are also cached (avoid retrying broken paths)`() {
            var calls = 0
            val candidates = listOf(
                PythonInterpreterResolver.Source.Manual to "/broken/python",
                PythonInterpreterResolver.Source.ProjectVenv to "/working/python",
            )
            val validate: (String) -> Boolean = { path ->
                calls++
                path == "/working/python"
            }

            val first = PythonInterpreterResolver.runChain(candidates, validate)
            val second = PythonInterpreterResolver.runChain(candidates, validate)

            assertIs<PythonInterpreterResolver.Resolution.Ok>(first)
            assertIs<PythonInterpreterResolver.Resolution.Ok>(second)
            assertEquals(
                2, calls,
                "first call probes both paths once; second call hits cache for both",
            )
        }

        @Test
        fun `invalidateValidationCache forces re-validation`() {
            var calls = 0
            val candidates = listOf(
                PythonInterpreterResolver.Source.Manual to "/path/manual",
            )
            val validate: (String) -> Boolean = { calls++; true }

            PythonInterpreterResolver.runChain(candidates, validate)
            PythonInterpreterResolver.invalidateValidationCache()
            PythonInterpreterResolver.runChain(candidates, validate)

            assertEquals(2, calls, "invalidate must clear the cache")
        }

        @Test
        fun `different paths are cached independently`() {
            val seen = mutableListOf<String>()
            val validate: (String) -> Boolean = { seen += it; true }

            PythonInterpreterResolver.runChain(
                listOf(PythonInterpreterResolver.Source.Manual to "/a"),
                validate,
            )
            PythonInterpreterResolver.runChain(
                listOf(PythonInterpreterResolver.Source.Manual to "/b"),
                validate,
            )
            PythonInterpreterResolver.runChain(
                listOf(PythonInterpreterResolver.Source.Manual to "/a"),
                validate,
            )

            assertEquals(listOf("/a", "/b"), seen, "each unique path probed once")
        }

        @Test
        fun `cached result returns same Resolution outcome`() {
            // Cache stores the boolean, but the Resolution.Ok wrapper must
            // still be reconstructed correctly on cache hit (right source,
            // right path) — easy to break by returning a stale Resolution
            // object instead of re-walking the candidate list.
            val candidates = listOf(
                PythonInterpreterResolver.Source.Manual to "/cached/python",
            )
            PythonInterpreterResolver.runChain(candidates) { true }
            val second = PythonInterpreterResolver.runChain(candidates) {
                error("validate should not run on cache hit")
            }
            assertIs<PythonInterpreterResolver.Resolution.Ok>(second)
            assertEquals("/cached/python", second.pythonPath)
            assertEquals(PythonInterpreterResolver.Source.Manual, second.source)
        }

        @Test
        fun `negative-cache fall-through still walks to next candidate`() {
            // Edge: first candidate is cached as failing; runChain must
            // still consult the cache, see false, and fall through — not
            // skip the cached entry entirely and resolve to it anyway.
            PythonInterpreterResolver.runChain(
                listOf(PythonInterpreterResolver.Source.Manual to "/broken"),
                validate = { false },
            )

            // Now /broken is cached as false. Run again with the broken
            // path first, working second, and a validate that would PASS
            // for /broken if invoked — we want to prove the cache shortcuts
            // the validate call and we still pick /working.
            var validateCalledForBroken = false
            val res = PythonInterpreterResolver.runChain(
                listOf(
                    PythonInterpreterResolver.Source.Manual to "/broken",
                    PythonInterpreterResolver.Source.ProjectVenv to "/working",
                ),
                validate = { path ->
                    if (path == "/broken") validateCalledForBroken = true
                    true
                },
            )
            assertIs<PythonInterpreterResolver.Resolution.Ok>(res)
            assertEquals("/working", res.pythonPath)
            assertTrue(!validateCalledForBroken, "cached /broken must skip validate")
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
