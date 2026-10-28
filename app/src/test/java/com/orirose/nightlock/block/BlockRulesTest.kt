package com.orirose.nightlock.block

import com.orirose.nightlock.data.Config
import com.orirose.nightlock.data.Essentials
import com.orirose.nightlock.data.Schedule
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockRulesTest {
    private val essentials = Essentials(
        locked = setOf("com.android.settings", "com.sec.android.app.clockpackage"),
        defaultAllowlist = setOf("com.android.settings", "com.sec.android.app.clockpackage", "com.samsung.android.messaging"),
    )

    // A Wednesday afternoon, outside any schedule used below.
    private val now = LocalDateTime.of(2026, 10, 28, 15, 0)

    @Test
    fun blocksChosenAppsOnlyWhileActive() {
        val config = Config(blockedPackages = setOf("com.google.android.youtube"))
        assertFalse(BlockRules.from(config, essentials, now).blocks("com.google.android.youtube"))
        assertTrue(BlockRules.from(config.copy(manualBlock = true), essentials, now).blocks("com.google.android.youtube"))
    }

    @Test
    fun neverBlocksUnchosenApps() {
        val rules = BlockRules.from(Config(blockedPackages = setOf("com.reddit.frontpage"), manualBlock = true), essentials, now)
        assertFalse(rules.blocks("com.google.android.youtube"))
    }

    @Test
    fun defaultAllowlistWinsOverTheBlockList() {
        val config = Config(blockedPackages = setOf("com.samsung.android.messaging", "com.reddit.frontpage"), manualBlock = true)
        assertEquals(setOf("com.reddit.frontpage"), BlockRules.from(config, essentials, now).blocked)
    }

    @Test
    fun editedAllowlistReplacesTheDefaults() {
        val config = Config(
            blockedPackages = setOf("com.samsung.android.messaging", "com.whatsapp"),
            allowedPackages = setOf("com.whatsapp"),
            manualBlock = true,
        )
        assertEquals(setOf("com.samsung.android.messaging"), BlockRules.from(config, essentials, now).blocked)
    }

    @Test
    fun essentialAppsAreNeverBlockedEvenWithAnEmptyAllowlist() {
        val config = Config(
            blockedPackages = setOf("com.android.settings", "com.sec.android.app.clockpackage"),
            allowedPackages = emptySet(),
            manualBlock = true,
        )
        assertTrue(BlockRules.from(config, essentials, now).blocked.isEmpty())
    }

    @Test
    fun essentialAppsAreNeverBlockedByASchedulesOwnList() {
        val schedule = Schedule(startMinute = 14 * 60, endMinute = 16 * 60, packages = setOf("com.android.settings", "com.reddit.frontpage"))
        val rules = BlockRules.from(Config(schedules = listOf(schedule)), essentials, now)
        assertEquals(setOf("com.reddit.frontpage"), rules.blocked)
    }
}
