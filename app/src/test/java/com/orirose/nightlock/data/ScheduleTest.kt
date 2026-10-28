package com.orirose.nightlock.data

import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleTest {
    // 2026-10-26 is a Monday.
    private fun at(day: Int, hour: Int, minute: Int = 0) = LocalDateTime.of(2026, 10, day, hour, minute)
    private val monday = 1
    private val tuesday = 2

    private val night = Schedule(id = "night", days = setOf(monday), startMinute = 23 * 60 + 30, endMinute = 7 * 60, warningMinutes = 15)
    private val afternoon = Schedule(id = "afternoon", days = setOf(monday), startMinute = 14 * 60, endMinute = 16 * 60, warningMinutes = 0)

    private fun Config.blocking(t: LocalDateTime) = isBlocking(t)
    private val main = setOf("main.app")

    @Test
    fun aScheduleWithNoAppsToBlockIsNotABlock() {
        assertFalse(Config(schedules = listOf(night)).blocking(at(26, 23, 45)))
        assertFalse(Config(blockedPackages = main, schedules = listOf(night.copy(packages = emptySet()))).blocking(at(26, 23, 45)))
        assertFalse(Config(manualBlock = true).blocking(at(26, 12, 0)))
    }

    @Test
    fun endTimeForOneAppIgnoresSchedulesThatDontBlockIt() {
        val morning = Schedule(id = "morning", name = "Morning", days = setOf(tuesday), startMinute = 7 * 60, endMinute = 8 * 60, warningMinutes = 0, packages = setOf("other.app"))
        val config = Config(blockedPackages = main, schedules = listOf(night, morning))
        assertEquals(at(27, 7, 0), config.blockEndsAt(at(26, 23, 45), "main.app"))
        assertEquals(at(27, 8, 0), config.blockEndsAt(at(26, 23, 45)))
        assertEquals(listOf("Morning"), config.activeScheduleNames(at(27, 7, 30), "other.app"))
        assertTrue(config.activeScheduleNames(at(27, 7, 30), "main.app").isEmpty())
    }

    @Test
    fun overnightScheduleRunsFromItsDayIntoTheNextMorning() {
        val config = Config(blockedPackages = main, schedules = listOf(night))
        assertFalse(config.blocking(at(26, 23, 29)))
        assertTrue(config.blocking(at(26, 23, 30)))
        assertTrue(config.blocking(at(27, 0, 0)))
        assertTrue(config.blocking(at(27, 6, 59)))
        assertFalse(config.blocking(at(27, 7, 0)))
        // Tuesday isn't one of its days, so Tuesday night is free, and so is the Sunday-to-Monday night.
        assertFalse(config.blocking(at(27, 23, 45)))
        assertFalse(config.blocking(at(26, 3, 0)))
    }

    @Test
    fun sameDayScheduleRunsOnlyOnItsDays() {
        val config = Config(blockedPackages = main, schedules = listOf(afternoon))
        assertTrue(config.blocking(at(26, 15, 0)))
        assertFalse(config.blocking(at(26, 16, 0)))
        assertFalse(config.blocking(at(27, 15, 0)))
    }

    @Test
    fun disabledAndInvalidSchedulesNeverRun() {
        assertFalse(Config(blockedPackages = main, schedules = listOf(night.copy(enabled = false))).blocking(at(26, 23, 45)))
        assertFalse(Config(blockedPackages = main, schedules = listOf(night.copy(days = emptySet()))).blocking(at(26, 23, 45)))
        assertFalse(Config(blockedPackages = main, schedules = listOf(night.copy(endMinute = night.startMinute))).blocking(at(26, 23, 45)))
    }

    @Test
    fun eachScheduleBlocksItsOwnListOrTheMainOne() {
        val config = Config(
            blockedPackages = setOf("main.app"),
            schedules = listOf(night, afternoon.copy(packages = setOf("own.app"))),
        )
        assertEquals(setOf("own.app"), config.blockedPackagesAt(at(26, 15, 0)))
        assertEquals(setOf("main.app"), config.blockedPackagesAt(at(26, 23, 45)))
        assertEquals(setOf("main.app"), config.copy(manualBlock = true).blockedPackagesAt(at(27, 12, 0)))
    }

    @Test
    fun endTimeFollowsBackToBackSchedules() {
        val morning = Schedule(id = "morning", days = setOf(tuesday), startMinute = 7 * 60, endMinute = 8 * 60, warningMinutes = 0)
        val config = Config(blockedPackages = main, schedules = listOf(night, morning))
        assertEquals(at(27, 8, 0), config.blockEndsAt(at(26, 23, 45)))
        assertNull(config.copy(manualBlock = true).blockEndsAt(at(26, 23, 45)))
        assertNull(config.blockEndsAt(at(26, 12, 0)))
    }

    @Test
    fun nextChangeIsTheWarningThenTheStartThenTheEnd() {
        val config = Config(blockedPackages = main, schedules = listOf(night))
        assertEquals(at(26, 23, 15), config.nextChangeAfter(at(26, 20, 0)))
        assertEquals(at(26, 23, 30), config.nextChangeAfter(at(26, 23, 15)))
        assertEquals(at(27, 7, 0), config.nextChangeAfter(at(26, 23, 30)))
        assertEquals(at(26, 23, 30), config.nextBlockStart(at(26, 20, 0))?.start)
    }

    @Test
    fun warningIsDueOnlyBetweenItsTimeAndTheStart() {
        val config = Config(blockedPackages = main, schedules = listOf(night))
        assertTrue(config.warningsDue(at(26, 23, 0)).isEmpty())
        assertEquals("night", config.warningsDue(at(26, 23, 20)).single().schedule.id)
        assertTrue(config.warningsDue(at(26, 23, 30)).isEmpty())
    }
}
