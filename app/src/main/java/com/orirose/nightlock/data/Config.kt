package com.orirose.nightlock.data

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.orirose.nightlock.LOG_TAG
import java.time.LocalDateTime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Everything the user sets up. Stored as one JSON document, so later milestones can add fields. */
@Serializable
data class Config(
    /** Apps the user picked to block. */
    val blockedPackages: Set<String> = emptySet(),
    /** The editable allowlist; null until the user first edits it, which means [Essentials.defaultAllowlist]. */
    val allowedPackages: Set<String>? = null,
    /** The "Block now" switch. */
    val manualBlock: Boolean = false,
    val schedules: List<Schedule> = emptyList(),
    /** Unlock by waiting: how long to wait on the unlock screen, in minutes (seconds in TEST_MODE). */
    val unlockWaitMinutes: Int = 3,
    /** Unlock by waiting: how long the pause it earns lasts, in minutes (seconds in TEST_MODE). */
    val waitPauseMinutes: Int = 15,
    /** While a pause runs, schedules and "Block now" block nothing until this moment; null when not paused. */
    @Serializable(with = LocalDateTimeSerializer::class)
    val pausedUntil: LocalDateTime? = null,
    /** Changes that make NightLock easier, waiting out their 24 hours. */
    val pending: List<Pending> = emptyList(),
    /** Daily allowances for one app or a group of apps. */
    val limits: List<TimeLimit> = emptyList(),
    /** Limits that ran out and were unlocked, by id: each one blocks nothing until its moment. */
    val limitPauses: Map<String, @Serializable(with = LocalDateTimeSerializer::class) LocalDateTime> = emptyMap(),
    /** The file the user chose for a copy of these settings, which outlives uninstalling; null if none. */
    val backupUri: String? = null,
) {
    /** True on a fresh install: nothing has been set up yet, so restoring a backup loses nothing. */
    val isUntouched: Boolean get() = copy(backupUri = null) == Config()

    fun allowlist(essentials: Essentials): Set<String> =
        (allowedPackages ?: essentials.defaultAllowlist) + essentials.locked

    /** Schedules that can run: switched on, fully set up, and with at least one app to block. */
    private val liveSchedules: List<Schedule>
        get() = schedules.filter { it.enabled && it.isValid && appsOf(it).isNotEmpty() }

    /** The apps a schedule blocks: its own list, or the main one. */
    fun appsOf(schedule: Schedule): Set<String> = schedule.packages ?: blockedPackages

    /** Every time a live schedule runs, from the day before [now] to a week after. */
    private fun occurrencesAround(now: LocalDateTime): List<Occurrence> =
        liveSchedules.flatMap { it.occurrences(now.toLocalDate()) }

    fun activeOccurrences(now: LocalDateTime): List<Occurrence> = occurrencesAround(now).filter { now in it }

    /** The "Block now" switch is on and has apps to block. */
    val manualBlocking: Boolean get() = manualBlock && blockedPackages.isNotEmpty()

    /**
     * True while "Block now" or a schedule is on for at least one app, paused or not. Settings that would
     * make NightLock easier are locked for this whole time. A limit that has run out doesn't count: it
     * would lock everything from the afternoon on, and limits have their own 24-hour rule.
     */
    fun isBlockTime(now: LocalDateTime): Boolean = manualBlocking || activeOccurrences(now).isNotEmpty()

    fun isPaused(now: LocalDateTime): Boolean = pausedUntil?.let { now < it } == true

    /** True while "Block now" or a schedule is blocking: block time, and no pause running. */
    fun schedulesBlocking(now: LocalDateTime): Boolean = isBlockTime(now) && !isPaused(now)

    private val liveLimits: List<TimeLimit> get() = limits.filter { it.isValid }

    /** The ids of the limits that cover any of [packages]. */
    fun limitsOn(packages: Collection<String>): Set<String> =
        liveLimits.filter { limit -> packages.any { it in limit.packages } }.mapTo(HashSet()) { it.id }

    /** The limits whose allowance for today is used up, whether they've been unlocked or not. */
    fun limitsReached(now: LocalDateTime, usage: DayUsage): List<TimeLimit> {
        val today = usage.on(limitDay(now))
        return liveLimits.filter { today.of(it) >= it.allowanceMs }
    }

    private fun isLimitPaused(limit: TimeLimit, now: LocalDateTime): Boolean = limitPauses[limit.id]?.let { now < it } == true

    /** The limits that are blocking at [now]: used up, and not unlocked. */
    fun limitsBlocking(now: LocalDateTime, usage: DayUsage): List<TimeLimit> =
        limitsReached(now, usage).filterNot { isLimitPaused(it, now) }

    /** True while apps are actually being blocked, for any reason. */
    fun isBlocking(now: LocalDateTime, usage: DayUsage): Boolean =
        schedulesBlocking(now) || limitsBlocking(now, usage).isNotEmpty()

    /** The apps blocked at [now], before the allowlist is taken out. */
    fun blockedPackagesAt(now: LocalDateTime, usage: DayUsage): Set<String> = buildSet {
        if (schedulesBlocking(now)) {
            if (manualBlock) addAll(blockedPackages)
            activeOccurrences(now).forEach { addAll(appsOf(it.schedule)) }
        }
        limitsBlocking(now, usage).forEach { addAll(it.packages) }
    }

    /** True if something would be blocking at [now] but for a pause. */
    fun hasPausedBlock(now: LocalDateTime, usage: DayUsage): Boolean =
        (isBlockTime(now) && isPaused(now)) || limitsReached(now, usage).any { isLimitPaused(it, now) }

    /** When the first running pause ends, or null if none is running. */
    fun pauseEndsAt(now: LocalDateTime): LocalDateTime? =
        (listOfNotNull(pausedUntil) + limitPauses.values).filter { it > now }.minOrNull()

    /**
     * Pauses what's blocking at [now] until [until]: schedules and "Block now" no later than their end, and
     * the limits that have run out no later than the end of their day. Nothing else is touched, so a schedule that
     * starts or another limit that runs out during the pause still blocks.
     */
    fun withPause(now: LocalDateTime, usage: DayUsage, until: LocalDateTime): Config = copy(
        pausedUntil = if (schedulesBlocking(now)) blockEndsAt(now)?.let { minOf(it, until) } ?: until else pausedUntil,
        limitPauses = limitPauses + limitsBlocking(now, usage).associate { it.id to minOf(until, nextLimitReset(now)) },
    )

    /**
     * Ends what's blocking at [now] for good: "Block now" goes off, running schedules pause until they end, at
     * most a day (schedules that touch end to end could otherwise run on for weeks), and the limits that have
     * run out pause until the end of their day, when they start over anyway.
     */
    fun withRestOff(now: LocalDateTime, usage: DayUsage): Config {
        val limitsOff = limitPauses + limitsBlocking(now, usage).associate { it.id to nextLimitReset(now) }
        if (!schedulesBlocking(now)) return copy(limitPauses = limitsOff)
        val withoutManual = copy(manualBlock = false)
        val until = withoutManual.blockEndsAt(now)?.let { minOf(it, now.plusDays(1)) }
        return withoutManual.copy(pausedUntil = until ?: pausedUntil, limitPauses = limitsOff)
    }

    /** How long until a limit among [counting] (ids) runs out, or null if none of them has any time left to run out. */
    fun millisToRunOut(now: LocalDateTime, usage: DayUsage, counting: Set<String>): Long? {
        val today = usage.on(limitDay(now))
        return liveLimits.filter { it.id in counting }.map { it.allowanceMs - today.of(it) }.filter { it > 0 }.minOrNull()
    }

    /**
     * When the block on [packageName] (or on anything, if null) ends, following schedules that start the
     * moment another ends. Null when nothing is running, or the "Block now" switch (no end) covers it.
     */
    fun blockEndsAt(now: LocalDateTime, packageName: String? = null): LocalDateTime? {
        if (manualBlocking && (packageName == null || packageName in blockedPackages)) return null
        fun runningFor(time: LocalDateTime) =
            activeOccurrences(time).filter { packageName == null || packageName in appsOf(it.schedule) }
        var end = runningFor(now).maxOfOrNull { it.end } ?: return null
        repeat(MAX_CHAINED) {
            end = runningFor(end).maxOfOrNull { it.end } ?: return end
        }
        return end
    }

    /** Names of the schedules blocking [packageName] (or anything, if null) at [now]. */
    fun activeScheduleNames(now: LocalDateTime, packageName: String? = null): List<String> =
        activeOccurrences(now).filter { packageName == null || packageName in appsOf(it.schedule) }
            .map { it.schedule.name }.distinct()

    /** The next schedule start after [now], or null if none in the coming week. */
    fun nextBlockStart(now: LocalDateTime): Occurrence? =
        occurrencesAround(now).filter { it.start > now }.minByOrNull { it.start }

    /** Warnings that should be showing at [now]: their warning time has come and their block hasn't started. */
    fun warningsDue(now: LocalDateTime): List<Occurrence> =
        occurrencesAround(now).filter { o -> o.warningAt?.let { now >= it && now < o.start } == true }

    /**
     * The next moment anything changes (a block starts or ends, a warning is due, a pause ends, a waiting
     * change applies, or limits start over), or null if never. A limit running out depends on
     * what's on screen: see [millisToRunOut].
     */
    fun nextChangeAfter(now: LocalDateTime): LocalDateTime? =
        (occurrencesAround(now).flatMap { listOfNotNull(it.start, it.end, it.warningAt) } +
            listOfNotNull(pausedUntil) + limitPauses.values + pending.map { it.applyAt } +
            listOfNotNull(nextLimitReset(now).takeIf { liveLimits.isNotEmpty() }))
            .filter { it > now }.minOrNull()

    /**
     * Tidies up at [now]: forgets pauses that have ended (so turning the clock back can't bring them back), and
     * applies waiting changes whose 24 hours are up, but never during a block: those wait for it to end, and a
     * change to a limit that has run out waits for the limit to start over.
     * Returns an equal config when there's nothing to do.
     */
    fun applyDue(now: LocalDateTime, essentials: Essentials, usage: DayUsage): Config {
        val base = copy(pausedUntil = pausedUntil?.takeIf { now < it }, limitPauses = limitPauses.filterValues { now < it })
        if (base.isBlockTime(now)) return base
        val reached = base.limitsReached(now, usage).map { it.id }
        val (due, waiting) = base.pending.partition { it.applyAt <= now && it.limitId !in reached }
        if (due.isEmpty()) return base
        return due.fold(base.copy(pending = waiting)) { config, change -> config.apply(change, essentials) }
    }

    private fun apply(change: Pending, essentials: Essentials): Config = when {
        change.unblock != null -> copy(blockedPackages = blockedPackages - change.unblock)
        change.allow != null -> copy(allowedPackages = (allowedPackages ?: essentials.defaultAllowlist) + change.allow)
        change.schedule != null -> copy(schedules = schedules.map { if (it.id == change.schedule.id) change.schedule else it })
        change.deleteSchedule != null -> copy(schedules = schedules.filterNot { it.id == change.deleteSchedule })
        change.unlockWaitMinutes != null -> copy(unlockWaitMinutes = change.unlockWaitMinutes)
        change.waitPauseMinutes != null -> copy(waitPauseMinutes = change.waitPauseMinutes)
        change.limit != null -> copy(limits = limits.map { if (it.id == change.limit.id) change.limit else it })
        change.deleteLimit != null -> copy(limits = limits.filterNot { it.id == change.deleteLimit })
        else -> this
    }
}

/**
 * One change that makes NightLock easier, applied at [applyAt] (24 hours after it was made). Exactly one
 * of the other fields is set.
 */
@Serializable
data class Pending(
    @Serializable(with = LocalDateTimeSerializer::class)
    val applyAt: LocalDateTime,
    /** Take this app off the main block list. */
    val unblock: String? = null,
    /** Add this app to the allowlist. */
    val allow: String? = null,
    /** Replace the schedule with this one's id. */
    val schedule: Schedule? = null,
    /** Delete the schedule with this id. */
    val deleteSchedule: String? = null,
    val unlockWaitMinutes: Int? = null,
    val waitPauseMinutes: Int? = null,
    /** Replace the limit with this one's id. */
    val limit: TimeLimit? = null,
    /** Delete the limit with this id. */
    val deleteLimit: String? = null,
) {
    val scheduleId: String? get() = schedule?.id ?: deleteSchedule
    val limitId: String? get() = limit?.id ?: deleteLimit
}

/** Back-to-back schedules followed when working out an end time; more than a week's worth. */
private const val MAX_CHAINED = 30

private val Context.dataStore by preferencesDataStore(name = "nightlock")
private val CONFIG_KEY = stringPreferencesKey("config")

class ConfigRepository(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }

    val config: Flow<Config> = context.dataStore.data.map { decode(it[CONFIG_KEY]) }

    private val _backupFailed = MutableStateFlow(false)

    /** True while the last attempt to write the backup file failed. */
    val backupFailed: StateFlow<Boolean> = _backupFailed.asStateFlow()

    /**
     * Changes the config in one atomic step, so [transform] always sees the latest saved version. Nothing is
     * written (and nobody is told) when the config comes back unchanged. Saved changes go to the backup file too.
     */
    suspend fun update(transform: (Config) -> Config) {
        var changed: Config? = null
        context.dataStore.edit { prefs ->
            val old = decode(prefs[CONFIG_KEY])
            val new = transform(old)
            if (new != old) {
                prefs[CONFIG_KEY] = json.encodeToString(Config.serializer(), new)
                changed = new
            }
        }
        changed?.let { backUp(it) }
    }

    /** Copies [config] to the backup file, if there is one. A blank config never replaces a real backup. */
    suspend fun backUp(config: Config) {
        val uri = config.backupUri ?: return
        if (!config.isUntouched) _backupFailed.value = !writeBackup(context, Uri.parse(uri), config)
    }

    private fun decode(text: String?): Config {
        if (text == null) return Config()
        return runCatching { json.decodeFromString(Config.serializer(), text) }
            .onFailure { Log.e(LOG_TAG, "Saved config is unreadable, starting from defaults", it) }
            .getOrDefault(Config())
    }
}
