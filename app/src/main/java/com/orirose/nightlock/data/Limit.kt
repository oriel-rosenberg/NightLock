package com.orirose.nightlock.data

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.orirose.nightlock.Durations
import com.orirose.nightlock.LOG_TAG
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A day for daily limits starts at this hour, not at midnight, so a late night still counts as the same day. */
const val LIMIT_DAY_START_HOUR = 2

/** The day [now] belongs to for daily limits: Tuesday 01:30 is still Monday's. */
fun limitDay(now: LocalDateTime): LocalDate = now.minusHours(LIMIT_DAY_START_HOUR.toLong()).toLocalDate()

/** When [day] starts for daily limits. */
private fun limitDayStart(day: LocalDate): LocalDateTime = day.atTime(LIMIT_DAY_START_HOUR, 0)

/** The next moment daily limits start over. */
fun nextLimitReset(now: LocalDateTime): LocalDateTime = limitDayStart(limitDay(now).plusDays(1))

/**
 * A daily allowance: [packages] may be on screen for [minutes] a day in total, then they're blocked until
 * the day ends at [LIMIT_DAY_START_HOUR]. One app makes an individual limit; several make a group that shares
 * the allowance. An app can be in any number of limits, and each one counts and runs out on its own.
 */
@Serializable
data class TimeLimit(
    val id: String = UUID.randomUUID().toString(),
    /** Optional for one app, whose own name is shown instead; needed for a group. */
    val name: String = "",
    val packages: Set<String> = emptySet(),
    val minutes: Int = 60,
) {
    val isValid: Boolean get() = packages.isNotEmpty() && minutes in 1 until Schedule.MINUTES_PER_DAY

    /** The allowance in milliseconds; seconds instead of minutes in TEST_MODE. */
    val allowanceMs: Long get() = Durations.minutes(minutes)

    /** True if this version allows no more than [old] did, so the change can apply at once. */
    fun isAtLeastAsStrictAs(old: TimeLimit): Boolean = minutes <= old.minutes && packages.containsAll(old.packages)

    fun displayName(labelOf: (String) -> String): String = name.ifBlank { packages.joinToString(" + ", transform = labelOf) }
}

/**
 * How much of each limit was used on one [day], by limit id. Counted per limit, not per app: a group whose
 * two apps share the screen uses its allowance once, and no history of app use is kept.
 */
@Serializable
data class DayUsage(
    @Serializable(with = LocalDateSerializer::class)
    val day: LocalDate = LocalDate.of(1970, 1, 1),
    val millis: Map<String, Long> = emptyMap(),
) {
    /** This usage if it's from [today]; a fresh start otherwise. */
    fun on(today: LocalDate): DayUsage = if (day == today) this else DayUsage(today)

    fun plus(limitIds: Set<String>, ms: Long): DayUsage =
        copy(millis = millis + limitIds.associateWith { (millis[it] ?: 0L) + ms })

    /** The usage for [now]'s day after [ms] more on [limitIds], ending at [now]. Any part before the day began was yesterday's. */
    fun after(limitIds: Set<String>, ms: Long, now: LocalDateTime): DayUsage {
        val day = limitDay(now)
        val sinceDayStart = Duration.between(limitDayStart(day), now).toMillis()
        return on(day).plus(limitIds, minOf(ms, sinceDayStart))
    }

    fun of(limit: TimeLimit): Long = millis[limit.id] ?: 0L
}

private val Context.usageStore by preferencesDataStore(name = "usage")
private val USAGE_KEY = stringPreferencesKey("day")

/** Keeps today's counts across restarts. A file of its own, so saving them doesn't look like a settings change. */
class UsageRepository(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }

    /** The saved counts, or a fresh start if they can't be read. */
    suspend fun load(): DayUsage = runCatching {
        context.usageStore.data.first()[USAGE_KEY]?.let { json.decodeFromString(DayUsage.serializer(), it) }
    }.getOrNull() ?: DayUsage()

    /** A failed save (a full disk, say) loses a little counting, never the blocker: it's logged and left. */
    suspend fun save(usage: DayUsage) {
        runCatching { context.usageStore.edit { it[USAGE_KEY] = json.encodeToString(DayUsage.serializer(), usage) } }
            .onFailure { Log.e(LOG_TAG, "Couldn't save today's usage", it) }
    }
}
