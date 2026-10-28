package com.orirose.nightlock.data

import com.orirose.nightlock.Durations
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID
import kotlinx.serialization.Serializable

/** A recurring block, e.g. "Bedtime, every day 23:30–07:00". Times are wall-clock times on the phone. */
@Serializable
data class Schedule(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "Bedtime",
    /** ISO day numbers (1 = Monday … 7 = Sunday) the block *starts* on. */
    val days: Set<Int> = (1..7).toSet(),
    /** Minutes after midnight. An end at or before the start means the block runs past midnight. */
    val startMinute: Int = 23 * 60 + 30,
    val endMinute: Int = 7 * 60,
    val enabled: Boolean = true,
    /** The apps this schedule blocks; null means the main block list. */
    val packages: Set<String>? = null,
    /** Minutes before the start to show a warning notification; 0 for none. */
    val warningMinutes: Int = 15,
) {
    val crossesMidnight: Boolean get() = endMinute < startMinute

    val isValid: Boolean
        get() = days.isNotEmpty() && days.all { it in 1..7 } &&
            startMinute in 0 until MINUTES_PER_DAY && endMinute in 0 until MINUTES_PER_DAY &&
            startMinute != endMinute && warningMinutes >= 0

    /** Each run of this schedule starting from the day before [day] to a week after it. */
    fun occurrences(day: LocalDate): List<Occurrence> = (-1L..7L)
        .map { day.plusDays(it) }
        .filter { it.dayOfWeek.value in days }
        .map { startDay ->
            val endDay = if (crossesMidnight) startDay.plusDays(1) else startDay
            Occurrence(this, startDay.atMinute(startMinute), endDay.atMinute(endMinute))
        }

    private fun LocalDate.atMinute(minute: Int): LocalDateTime = atTime(minute / 60, minute % 60)

    companion object {
        const val MINUTES_PER_DAY = 24 * 60
    }
}

/** One run of a schedule, from [start] up to (not including) [end]. */
data class Occurrence(val schedule: Schedule, val start: LocalDateTime, val end: LocalDateTime) {
    operator fun contains(time: LocalDateTime): Boolean = time >= start && time < end

    /** When to warn, or null if this schedule has no warning. Scaled to seconds in TEST_MODE. */
    val warningAt: LocalDateTime?
        get() = schedule.warningMinutes.takeIf { it > 0 }?.let { start.minus(Duration.ofMillis(Durations.minutes(it))) }
}
