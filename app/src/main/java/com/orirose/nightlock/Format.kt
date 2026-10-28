package com.orirose.nightlock

import android.content.Context
import android.text.format.DateFormat
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale

// The app is in English, so names (days, AM/PM) are too, even on a phone set to another language.

/** A clock time in the phone's 12/24-hour setting. */
fun Context.formatTime(time: LocalTime): String =
    time.format(DateTimeFormatter.ofPattern(if (DateFormat.is24HourFormat(this)) "HH:mm" else "h:mm a", Locale.ENGLISH))

fun Context.formatMinuteOfDay(minute: Int): String = formatTime(LocalTime.of(minute / 60, minute % 60))

/** "23:30", "tomorrow 07:00" or "Mon 23:30", relative to [now]. */
fun Context.formatWhen(time: LocalDateTime, now: LocalDateTime): String {
    val clock = formatTime(time.toLocalTime())
    return when (time.toLocalDate()) {
        now.toLocalDate() -> clock
        now.toLocalDate().plusDays(1) -> "tomorrow $clock"
        else -> "${dayName(time.dayOfWeek.value)} $clock"
    }
}

/** "6 h 12 min", "45 min" or "under a minute". */
fun formatDuration(duration: Duration): String {
    val minutes = duration.toMinutes()
    return when {
        minutes < 1 -> "under a minute"
        minutes < 60 -> "$minutes min"
        minutes % 60 == 0L -> "${minutes / 60} h"
        else -> "${minutes / 60} h ${minutes % 60} min"
    }
}

fun dayName(isoDay: Int, style: TextStyle = TextStyle.SHORT): String =
    DayOfWeek.of(isoDay).getDisplayName(style, Locale.ENGLISH)

/** ISO day numbers in the order the phone's region runs its week (Sunday first in Israel and the US). */
fun weekOrder(): List<Int> {
    val first = WeekFields.of(Locale.getDefault()).firstDayOfWeek.value
    return (0 until 7).map { (first - 1 + it) % 7 + 1 }
}

fun describeDays(days: Set<Int>): String =
    if (days.size == 7) "Every day" else weekOrder().filter { it in days }.joinToString(" ") { dayName(it) }
