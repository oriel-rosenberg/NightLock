package com.orirose.nightlock.data

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.orirose.nightlock.LOG_TAG
import java.time.LocalDateTime
import kotlinx.coroutines.flow.Flow
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
) {
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

    /** True while "Block now" or a schedule is blocking at least one app. */
    fun isBlocking(now: LocalDateTime): Boolean = manualBlocking || activeOccurrences(now).isNotEmpty()

    /** The apps blocked at [now], before the allowlist is taken out. */
    fun blockedPackagesAt(now: LocalDateTime): Set<String> = buildSet {
        if (manualBlock) addAll(blockedPackages)
        activeOccurrences(now).forEach { addAll(appsOf(it.schedule)) }
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

    /** The next moment anything changes (a block starts or ends, a warning is due), or null if never. */
    fun nextChangeAfter(now: LocalDateTime): LocalDateTime? =
        occurrencesAround(now).flatMap { listOfNotNull(it.start, it.end, it.warningAt) }.filter { it > now }.minOrNull()
}

/** Back-to-back schedules followed when working out an end time; more than a week's worth. */
private const val MAX_CHAINED = 30

private val Context.dataStore by preferencesDataStore(name = "nightlock")
private val CONFIG_KEY = stringPreferencesKey("config")

class ConfigRepository(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }

    val config: Flow<Config> = context.dataStore.data.map { decode(it[CONFIG_KEY]) }

    /** Changes the config in one atomic step, so [transform] always sees the latest saved version. */
    suspend fun update(transform: (Config) -> Config) {
        context.dataStore.edit { prefs ->
            prefs[CONFIG_KEY] = json.encodeToString(Config.serializer(), transform(decode(prefs[CONFIG_KEY])))
        }
    }

    private fun decode(text: String?): Config {
        if (text == null) return Config()
        return runCatching { json.decodeFromString(Config.serializer(), text) }
            .onFailure { Log.e(LOG_TAG, "Saved config is unreadable, starting from defaults", it) }
            .getOrDefault(Config())
    }
}
