package dev.kouko.intellijdbtree.lineage

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests over the [DbtProjectDetector.SKIP_PATH_PATTERN] regex. The full
 * `findAll` walk needs an IntelliJ Project + VirtualFile system, so it's
 * exercised by manual / sandbox testing instead. The skip rule itself is a
 * pure regex and is the part most likely to silently break (e.g. a future
 * change that accidentally matches user folders named `target_metrics`).
 */
class DbtProjectDetectorTest {

    private val skip = DbtProjectDetector.SKIP_PATH_PATTERN

    @Test
    fun `skips dbt build artifacts under any depth`() {
        assertTrue("/repo/dbt_proj/target".matches(skip))
        assertTrue("/repo/dbt_proj/target/run/manifest.json".matches(skip))
        assertTrue("/repo/dbt_proj/dbt_packages".matches(skip))
        assertTrue("/repo/dbt_proj/dbt_packages/dbt_utils/macros/x.sql".matches(skip))
    }

    @Test
    fun `skips python virtualenvs`() {
        assertTrue("/repo/.venv".matches(skip))
        assertTrue("/repo/.venv/lib/site-packages".matches(skip))
        assertTrue("/repo/venv".matches(skip))
        assertTrue("/repo/venv/bin/python".matches(skip))
    }

    @Test
    fun `skips node_modules and git`() {
        assertTrue("/repo/frontend/node_modules".matches(skip))
        assertTrue("/repo/.git".matches(skip))
        assertTrue("/repo/.git/objects/pack".matches(skip))
    }

    @Test
    fun `skips other build outputs`() {
        assertTrue("/repo/build".matches(skip))
        assertTrue("/repo/dist".matches(skip))
        assertTrue("/repo/__pycache__".matches(skip))
        assertTrue("/repo/src/__pycache__/foo.cpython.pyc".matches(skip))
    }

    @Test
    fun `does not match files with skip-keyword as substring`() {
        // 'target_metrics' contains 'target' but is not a target/ directory
        assertFalse("/repo/dbt_proj/target_metrics/foo.sql".matches(skip))
        assertFalse("/repo/build_helpers/x.kt".matches(skip))
        assertFalse("/repo/distribution/notes.md".matches(skip))
    }

    @Test
    fun `does not match arbitrary user folders`() {
        assertFalse("/repo/dbt_proj/models/staging/stg_orders.sql".matches(skip))
        assertFalse("/repo/dbt_proj/dbt_project.yml".matches(skip))
        assertFalse("/repo/src/main/kotlin/Foo.kt".matches(skip))
    }

    @Test
    fun `does not match a top-level path that lacks a leading directory`() {
        // The regex requires '/' before the skip keyword, so "target/foo" alone
        // (no parent) doesn't match. In practice IntelliJ always gives absolute
        // paths so this case shouldn't occur — documented here for clarity.
        assertFalse("target/foo".matches(skip))
        assertFalse("dbt_packages".matches(skip))
    }
}
