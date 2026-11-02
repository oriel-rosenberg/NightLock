package com.orirose.nightlock.data

import com.orirose.nightlock.Durations
import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LimitTest {
    // 2026-10-26 is a Monday.
    private fun at(day: Int, hour: Int, minute: Int = 0) = LocalDateTime.of(2026, 10, day, hour, minute)
    private val monday: LocalDate = LocalDate.of(2026, 10, 26)
    private val essentials = Essentials(locked = emptySet(), defaultAllowlist = emptySet())

    private val twitter = TimeLimit(id = "twitter", packages = setOf("x.app"), minutes = 60)
    private val facebook = TimeLimit(id = "facebook", packages = setOf("fb.app"), minutes = 60)
    private val social = TimeLimit(id = "social", name = "Social", packages = setOf("x.app", "fb.app"), minutes = 90)
    private val config = Config(limits = listOf(twitter, facebook, social))
    private val noon = at(26, 12)

    private fun used(vararg minutes: Pair<String, Int>) = DayUsage(monday, minutes.associate { (id, m) -> id to Durations.minutes(m) })

    @Test
    fun anIndividualLimitBlocksOnlyItsApp() {
        val usage = used("twitter" to 60, "social" to 60)
        assertTrue(config.isBlocking(noon, usage))
        assertEquals(setOf("x.app"), config.blockedPackagesAt(noon, usage))
    }

    @Test
    fun aGroupLimitBlocksAllItsAppsEvenIfEachHasTimeLeft() {
        val usage = used("twitter" to 45, "facebook" to 45, "social" to 90)
        assertEquals(setOf("x.app", "fb.app"), config.blockedPackagesAt(noon, usage))
    }

    @Test
    fun nothingIsBlockedBelowTheAllowance() {
        assertFalse(config.isBlocking(noon, used("twitter" to 59, "social" to 89)))
    }

    @Test
    fun theDayRunsFromTwoToTwoSoALateNightIsStillTheSameDay() {
        val usage = used("twitter" to 60, "social" to 90)
        assertTrue(config.isBlocking(at(26, 23, 59), usage))
        assertTrue(config.isBlocking(at(27, 1, 59), usage))
        assertFalse(config.isBlocking(at(27, 2, 0), usage))
        assertEquals(monday, limitDay(at(27, 1, 59)))
        assertEquals(LocalDate.of(2026, 10, 27), limitDay(at(27, 2, 0)))
        assertEquals(at(27, 2, 0), nextLimitReset(at(26, 12)))
        assertEquals(at(27, 2, 0), nextLimitReset(at(27, 1, 59)))
        assertEquals(at(28, 2, 0), nextLimitReset(at(27, 2, 0)))
    }

    @Test
    fun usageFromAnotherDayDoesntCount() {
        val usage = used("twitter" to 60, "social" to 90)
        assertEquals(LocalDate.of(2026, 10, 27), usage.on(LocalDate.of(2026, 10, 27)).day)
        assertTrue(usage.on(LocalDate.of(2026, 10, 27)).millis.isEmpty())
    }

    @Test
    fun anUnlockPausesOnlyTheLimitsThatHaveRunOut() {
        val out = used("twitter" to 60)
        val paused = config.withPause(noon, out, noon.plusMinutes(30))
        assertFalse(paused.isBlocking(noon, out))
        // Facebook running out during the pause still blocks, and Twitter comes back when the pause ends.
        val both = used("twitter" to 60, "facebook" to 60)
        assertEquals(setOf("fb.app"), paused.blockedPackagesAt(noon.plusMinutes(10), both))
        assertEquals(setOf("x.app", "fb.app"), paused.blockedPackagesAt(noon.plusMinutes(30), both))
        assertTrue(paused.hasPausedBlock(noon, out))
        assertEquals(noon.plusMinutes(30), paused.pauseEndsAt(noon))
    }

    @Test
    fun aGroupThatRunsOutDuringAnIndividualPauseStillBlocks() {
        val out = used("twitter" to 60, "social" to 80)
        val paused = config.withPause(noon, out, noon.plusMinutes(30))
        assertEquals(setOf("x.app", "fb.app"), paused.blockedPackagesAt(noon.plusMinutes(10), used("twitter" to 70, "social" to 90)))
    }

    @Test
    fun anUnlockedLimitDoesntPauseASchedule() {
        val bedtime = Schedule(id = "bed", days = setOf(1), startMinute = 23 * 60, endMinute = 7 * 60, warningMinutes = 0)
        val withSchedule = config.copy(blockedPackages = setOf("x.app"), schedules = listOf(bedtime))
        val out = used("twitter" to 60)
        val evening = at(26, 22)
        val restOfToday = withSchedule.withRestOff(evening, out)
        assertFalse(restOfToday.isBlocking(evening, out))
        assertTrue(restOfToday.isBlocking(at(26, 23, 30), out))
        assertEquals(setOf("x.app"), restOfToday.blockedPackagesAt(at(26, 23, 30), out))
    }

    @Test
    fun aPauseNeverOutlastsTheDayForALimit() {
        val out = used("twitter" to 60)
        val late = at(27, 1, 50)
        val paused = config.withPause(late, out, late.plusMinutes(30))
        assertEquals(at(27, 2, 0), paused.limitPauses["twitter"])
    }

    @Test
    fun restOfTheBlockTakesBothKindsOfBlock() {
        val bedtime = Schedule(id = "bed", days = setOf(1), startMinute = 20 * 60, endMinute = 7 * 60, warningMinutes = 0)
        val withSchedule = config.copy(blockedPackages = setOf("other.app"), schedules = listOf(bedtime))
        val out = used("twitter" to 60)
        val evening = at(26, 21)
        val off = withSchedule.withRestOff(evening, out)
        assertEquals(at(27, 7, 0), off.pausedUntil)
        assertEquals(at(27, 2, 0), off.limitPauses["twitter"])
        assertFalse(off.isBlocking(evening, out))
    }

    @Test
    fun runningOutCountsOnlyFromLimitsOnScreenWithTimeLeft() {
        val usage = used("twitter" to 50, "social" to 85)
        assertEquals(Durations.minutes(5), config.millisToRunOut(noon, usage, setOf("twitter", "social")))
        assertEquals(Durations.minutes(10), config.millisToRunOut(noon, usage, setOf("twitter")))
        assertNull(config.millisToRunOut(noon, used("twitter" to 60), setOf("twitter")))
        assertNull(config.millisToRunOut(noon, usage, emptySet()))
    }

    @Test
    fun usageAddsUpPerLimitSoAGroupCountsSharedScreenTimeOnce() {
        val usage = DayUsage(monday).plus(setOf("twitter", "social"), 1000).plus(setOf("facebook", "social"), 500)
        assertEquals(1000, usage.of(twitter))
        assertEquals(500, usage.of(facebook))
        assertEquals(1500, usage.of(social))
    }

    @Test
    fun aStretchAcrossTheResetCountsOnlyThePartAfterIt() {
        val twentyMinutes = Durations.minutes(20)
        val usage = used("twitter" to 50).after(setOf("twitter"), twentyMinutes, at(27, 2, 5))
        assertEquals(LocalDate.of(2026, 10, 27), usage.day)
        assertEquals(Durations.minutes(5), usage.of(twitter))
    }

    @Test
    fun aStretchAcrossMidnightIsStillTheSameDay() {
        val usage = used("twitter" to 50).after(setOf("twitter"), Durations.minutes(20), at(27, 0, 5))
        assertEquals(monday, usage.day)
        assertEquals(Durations.minutes(70), usage.of(twitter))
    }

    @Test
    fun aStretchWithinTheDayCountsInFull() {
        val usage = used("twitter" to 50).after(setOf("twitter"), Durations.minutes(5), noon)
        assertEquals(Durations.minutes(55), usage.of(twitter))
    }

    @Test
    fun limitsOnFindsEveryLimitCoveringAnApp() {
        assertEquals(setOf("twitter", "social"), config.limitsOn(listOf("x.app", "launcher.app")))
        assertTrue(config.limitsOn(listOf("launcher.app")).isEmpty())
    }

    @Test
    fun lessTimeOrMoreAppsIsStricterAndTheReverseIsEasier() {
        assertTrue(twitter.copy(minutes = 30).isAtLeastAsStrictAs(twitter))
        assertTrue(twitter.copy(name = "X").isAtLeastAsStrictAs(twitter))
        assertTrue(social.copy(packages = social.packages + "ig.app").isAtLeastAsStrictAs(social))
        assertFalse(twitter.copy(minutes = 61).isAtLeastAsStrictAs(twitter))
        assertFalse(social.copy(packages = setOf("x.app")).isAtLeastAsStrictAs(social))
    }

    @Test
    fun aRaisedLimitWaitsForTheResetIfItHasRunOut() {
        val raised = config.copy(pending = listOf(Pending(applyAt = at(26, 9), limit = twitter.copy(minutes = 120))))
        val out = used("twitter" to 60)
        assertEquals(raised, raised.applyDue(noon, essentials, out))
        assertEquals(raised, raised.applyDue(at(27, 1), essentials, out))
        assertEquals(120, raised.applyDue(noon, essentials, used("twitter" to 30)).limits.first().minutes)
        assertEquals(120, raised.applyDue(at(27, 2), essentials, out).limits.first().minutes)
    }

    @Test
    fun aDeletedLimitGoesOnceItsTimeComes() {
        val deleting = config.copy(pending = listOf(Pending(applyAt = noon, deleteLimit = "twitter")))
        assertEquals(deleting, deleting.applyDue(at(26, 11), essentials, DayUsage()))
        assertEquals(listOf(facebook, social), deleting.applyDue(noon, essentials, DayUsage()).limits)
    }

    @Test
    fun limitsStartOverAtTwoAndEndedPausesAreForgotten() {
        assertEquals(at(27, 2, 0), config.nextChangeAfter(noon))
        val paused = config.copy(limitPauses = mapOf("twitter" to at(26, 12, 30)))
        assertEquals(at(26, 12, 30), paused.nextChangeAfter(noon))
        assertTrue(paused.applyDue(at(26, 12, 30), essentials, DayUsage()).limitPauses.isEmpty())
    }

    @Test
    fun aLimitWithNoAppsOrNoTimeNeverBlocks() {
        val odd = Config(limits = listOf(TimeLimit(id = "a", packages = emptySet()), TimeLimit(id = "b", packages = setOf("x.app"), minutes = 0)))
        assertFalse(odd.isBlocking(noon, used("a" to 100, "b" to 100)))
    }

    @Test
    fun limitsAndUsageSurviveSaving() {
        val json = kotlinx.serialization.json.Json
        val saved = config.copy(limitPauses = mapOf("twitter" to at(26, 12, 30)), pending = listOf(Pending(applyAt = noon, limit = twitter)))
        assertEquals(saved, json.decodeFromString(Config.serializer(), json.encodeToString(Config.serializer(), saved)))
        val usage = used("twitter" to 5)
        assertEquals(usage, json.decodeFromString(DayUsage.serializer(), json.encodeToString(DayUsage.serializer(), usage)))
    }
}
