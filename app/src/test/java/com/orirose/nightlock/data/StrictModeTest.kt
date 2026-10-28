package com.orirose.nightlock.data

import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StrictModeTest {
    private val main = setOf("main.app")
    private val night = Schedule(id = "night", days = setOf(1, 2, 3), startMinute = 23 * 60 + 30, endMinute = 7 * 60, warningMinutes = 0)
    private val essentials = Essentials(locked = setOf("com.android.settings"), defaultAllowlist = setOf("com.android.settings", "sms.app"))

    // 2026-10-26 is a Monday.
    private fun at(day: Int, hour: Int, minute: Int = 0) = LocalDateTime.of(2026, 10, day, hour, minute)

    @Test
    fun moreDaysAndALongerWindowAreStricter() {
        assertTrue(night.copy(days = night.days + 4).isAtLeastAsStrictAs(night, main))
        assertTrue(night.copy(startMinute = 22 * 60, endMinute = 8 * 60).isAtLeastAsStrictAs(night, main))
        assertTrue(night.copy(name = "Renamed", warningMinutes = 30).isAtLeastAsStrictAs(night, main))
    }

    @Test
    fun fewerDaysAShorterWindowOrSwitchingOffIsEasier() {
        assertFalse(night.copy(days = setOf(1)).isAtLeastAsStrictAs(night, main))
        assertFalse(night.copy(startMinute = 23 * 60 + 45).isAtLeastAsStrictAs(night, main))
        assertFalse(night.copy(endMinute = 6 * 60).isAtLeastAsStrictAs(night, main))
        // Ending before midnight instead of after it is much shorter, even though 23:59 > 07:00.
        assertFalse(night.copy(endMinute = 23 * 60 + 59).isAtLeastAsStrictAs(night, main))
        assertFalse(night.copy(enabled = false).isAtLeastAsStrictAs(night, main))
    }

    @Test
    fun fewerAppsIsEasierAndMoreIsStricter() {
        val own = night.copy(packages = setOf("a", "b"))
        assertFalse(own.copy(packages = setOf("a")).isAtLeastAsStrictAs(own, main))
        assertTrue(own.copy(packages = setOf("a", "b", "c")).isAtLeastAsStrictAs(own, main))
        assertFalse(night.copy(packages = setOf("other")).isAtLeastAsStrictAs(night, main))
        assertTrue(night.copy(packages = main + "other").isAtLeastAsStrictAs(night, main))
    }

    @Test
    fun anyChangeToAScheduleThatNeverRunsIsFine() {
        val off = night.copy(enabled = false)
        assertTrue(night.copy(days = setOf(1)).isAtLeastAsStrictAs(off, main))
    }

    @Test
    fun aPauseStopsBlockingButNotTheLock() {
        val config = Config(blockedPackages = main, schedules = listOf(night), pausedUntil = at(27, 0, 15))
        assertTrue(config.isBlockTime(at(27, 0, 0)))
        assertTrue(config.isPaused(at(27, 0, 0)))
        assertFalse(config.isBlocking(at(27, 0, 0)))
        assertTrue(config.isBlocking(at(27, 0, 15)))
        assertEquals(at(27, 0, 15), config.nextChangeAfter(at(27, 0, 0)))
    }

    @Test
    fun waitingChangesApplyOnlyOnceTheirTimeComes() {
        val config = Config(
            blockedPackages = main + "x.app",
            schedules = listOf(night),
            pending = listOf(
                Pending(applyAt = at(27, 12), unblock = "x.app"),
                Pending(applyAt = at(27, 12), allow = "chat.app"),
                Pending(applyAt = at(28, 12), deleteSchedule = "night"),
            ),
        )
        assertEquals(config, config.applyDue(at(27, 11), essentials))
        val applied = config.applyDue(at(27, 12), essentials)
        assertEquals(main, applied.blockedPackages)
        assertEquals(setOf("com.android.settings", "sms.app", "chat.app"), applied.allowedPackages)
        assertEquals(listOf(night), applied.schedules)
        assertEquals(1, applied.pending.size)
        assertTrue(applied.applyDue(at(28, 12), essentials).schedules.isEmpty())
        assertEquals(at(27, 12), config.nextChangeAfter(at(27, 8)))
    }

    @Test
    fun waitingChangesHoldOffUntilTheBlockEnds() {
        val config = Config(
            blockedPackages = main,
            schedules = listOf(night),
            pending = listOf(Pending(applyAt = at(26, 23, 45), deleteSchedule = "night")),
        )
        // Due at 23:45 Monday, but the block runs until 07:00 Tuesday, paused or not.
        assertEquals(config, config.applyDue(at(27, 1), essentials))
        assertEquals(config.copy(pausedUntil = at(27, 1, 30)), config.copy(pausedUntil = at(27, 1, 30)).applyDue(at(27, 1), essentials))
        assertTrue(config.applyDue(at(27, 7), essentials).schedules.isEmpty())
    }

    @Test
    fun anEndedPauseIsForgotten() {
        val config = Config(blockedPackages = main, schedules = listOf(night), pausedUntil = at(27, 0, 15))
        assertNull(config.applyDue(at(27, 0, 15), essentials).pausedUntil)
        assertEquals(at(27, 0, 15), config.applyDue(at(27, 0, 10), essentials).pausedUntil)
    }

    @Test
    fun theWholeConfigSurvivesSaving() {
        val json = kotlinx.serialization.json.Json
        val config = Config(pausedUntil = at(27, 0, 15), pending = listOf(Pending(applyAt = at(27, 12), unlockWaitMinutes = 1)))
        assertEquals(config, json.decodeFromString(Config.serializer(), json.encodeToString(Config.serializer(), config)))
        assertNull(json.decodeFromString(Config.serializer(), "{}").pausedUntil)
    }
}
