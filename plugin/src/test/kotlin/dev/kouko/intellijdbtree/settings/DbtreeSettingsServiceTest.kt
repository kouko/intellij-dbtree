package dev.kouko.intellijdbtree.settings

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Pure-function tests over [DbtreeSettingsService.clampTimeoutSeconds].
 * Locks the contract that out-of-range or corrupted values fall back to
 * the default — important because settings are persisted as XML and could
 * be hand-edited or arrive from an older version with different bounds.
 */
class DbtreeSettingsServiceTest {

    @Test
    fun `in-range value passes through unchanged`() {
        assertEquals(30, DbtreeSettingsService.clampTimeoutSeconds(30))
        assertEquals(
            DbtreeSettingsService.TIMEOUT_MIN_SECONDS,
            DbtreeSettingsService.clampTimeoutSeconds(DbtreeSettingsService.TIMEOUT_MIN_SECONDS),
        )
        assertEquals(
            DbtreeSettingsService.TIMEOUT_MAX_SECONDS,
            DbtreeSettingsService.clampTimeoutSeconds(DbtreeSettingsService.TIMEOUT_MAX_SECONDS),
        )
    }

    @Test
    fun `below-min value falls back to default — not silently truncated`() {
        // We DON'T want to clamp 1ms → 5s silently, because that would mask
        // the misconfiguration. Reset to default makes the bug observable
        // (user sees "why is my timeout 15s? I set 1!") and self-correctable.
        assertEquals(
            DbtreeSettingsService.DEFAULT_TIMEOUT_SECONDS,
            DbtreeSettingsService.clampTimeoutSeconds(0),
        )
        assertEquals(
            DbtreeSettingsService.DEFAULT_TIMEOUT_SECONDS,
            DbtreeSettingsService.clampTimeoutSeconds(-1),
        )
        assertEquals(
            DbtreeSettingsService.DEFAULT_TIMEOUT_SECONDS,
            DbtreeSettingsService.clampTimeoutSeconds(DbtreeSettingsService.TIMEOUT_MIN_SECONDS - 1),
        )
    }

    @Test
    fun `above-max value falls back to default — protects against runaway timeouts`() {
        // A 1-hour timeout from a typo would hang the IDE on every column
        // click. Default is safer than truncating to TIMEOUT_MAX.
        assertEquals(
            DbtreeSettingsService.DEFAULT_TIMEOUT_SECONDS,
            DbtreeSettingsService.clampTimeoutSeconds(3600),
        )
        assertEquals(
            DbtreeSettingsService.DEFAULT_TIMEOUT_SECONDS,
            DbtreeSettingsService.clampTimeoutSeconds(DbtreeSettingsService.TIMEOUT_MAX_SECONDS + 1),
        )
    }
}
