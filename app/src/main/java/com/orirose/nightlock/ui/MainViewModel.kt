package com.orirose.nightlock.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.orirose.nightlock.Durations
import com.orirose.nightlock.block.Pauses
import com.orirose.nightlock.configRepository
import com.orirose.nightlock.data.AppEntry
import com.orirose.nightlock.data.Config
import com.orirose.nightlock.data.Essentials
import com.orirose.nightlock.data.Pending
import com.orirose.nightlock.data.Schedule
import com.orirose.nightlock.data.loadLaunchableApps
import com.orirose.nightlock.formatWhen
import java.time.Duration
import java.time.LocalDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * All changes to the settings go through here, and here strict mode is enforced:
 * - a change that makes NightLock stricter applies at once;
 * - one that makes it easier is refused during a block (paused or not), and otherwise waits 24 hours,
 *   except switching off or deleting a whole schedule, which applies at once outside a block;
 * - undoing a waiting change, which is stricter, always works.
 */
class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val repository = app.configRepository

    val config: StateFlow<Config?> = repository.config.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val essentials: StateFlow<Essentials?> = flow {
        emit(withContext(Dispatchers.Default) { Essentials.detect(app) })
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _apps = MutableStateFlow<List<AppEntry>?>(null)
    val apps: StateFlow<List<AppEntry>?> = _apps.asStateFlow()

    init {
        refreshApps()
    }

    /** Reloads the installed apps. The old list stays on screen until the new one is ready. */
    fun refreshApps() {
        viewModelScope.launch { _apps.value = withContext(Dispatchers.IO) { loadLaunchableApps(getApplication()) } }
    }

    /** Applies waiting changes whose 24 hours are up. Called when the app comes to the front. */
    fun applyDueChanges(essentials: Essentials) = change { config, now -> Outcome(config.applyDue(now, essentials)) }

    /** Switching it off while it blocks something is an unlock; it can only be switched off here when it doesn't. */
    fun setManualBlock(on: Boolean) = change { config, _ ->
        when {
            on || !config.manualBlocking -> Outcome(config.copy(manualBlock = on))
            else -> Outcome(config, "To switch Block now off, use Unlock.")
        }
    }

    fun setBlocked(packageName: String, blocked: Boolean) = change { config, now ->
        val waiting = config.pending.firstOrNull { it.unblock == packageName }
        when {
            // Tapping an app already waiting to be unblocked means "never mind": keep it blocked.
            waiting != null -> Outcome(config.copy(pending = config.pending - waiting), "Kept blocked.")
            blocked -> Outcome(config.copy(blockedPackages = config.blockedPackages + packageName))
            packageName !in config.blockedPackages -> Outcome(config)
            else -> easier(config, now, Pending(applyAt = cooldownEnd(now), unblock = packageName))
        }
    }

    fun setAllowed(packageName: String, allowed: Boolean, essentials: Essentials) = change { config, now ->
        val waiting = config.pending.firstOrNull { it.allow == packageName }
        val current = config.allowedPackages ?: essentials.defaultAllowlist
        when {
            packageName in essentials.locked -> Outcome(config)
            // Tapping an app already waiting to be allowed calls it off; that, and removing, are stricter.
            waiting != null -> Outcome(config.copy(pending = config.pending - waiting), "Won't be added.")
            !allowed -> Outcome(config.copy(allowedPackages = current - packageName))
            packageName in current -> Outcome(config)
            else -> easier(config, now, Pending(applyAt = cooldownEnd(now), allow = packageName))
        }
    }

    /** Adds a new schedule at once; changes to an existing one apply at once only if they're stricter. */
    fun saveSchedule(schedule: Schedule) = change { config, now ->
        val old = config.schedules.firstOrNull { it.id == schedule.id }
        val withoutWaiting = config.copy(pending = config.pending.filterNot { it.scheduleId == schedule.id })
        when {
            old == null -> Outcome(config.copy(schedules = config.schedules + schedule))
            schedule.isAtLeastAsStrictAs(old, config.blockedPackages) ->
                Outcome(withoutWaiting.copy(schedules = config.schedules.map { if (it.id == schedule.id) schedule else it }))
            else -> easier(withoutWaiting, now, Pending(applyAt = cooldownEnd(now), schedule = schedule))
        }
    }

    /**
     * Deleting a schedule applies at once (no 24-hour wait, by the user's choice), but not during a block.
     * Its waiting changes go with it.
     */
    fun deleteSchedule(id: String) = change { config, now ->
        val old = config.schedules.firstOrNull { it.id == id } ?: return@change Outcome(config)
        if (config.isBlockTime(now) && old.enabled && old.isValid) return@change Outcome(config, LOCKED)
        Outcome(config.copy(schedules = config.schedules - old, pending = config.pending.filterNot { it.scheduleId == id }))
    }

    /**
     * Switching a schedule on applies at once (stricter). Switching it off also applies at once (no 24-hour
     * wait, by the user's choice), but not during a block; its waiting changes are dropped so none of them
     * switches it back on later.
     */
    fun setScheduleEnabled(id: String, enabled: Boolean) = change { config, now ->
        val old = config.schedules.firstOrNull { it.id == id } ?: return@change Outcome(config)
        if (!enabled && config.isBlockTime(now) && old.enabled) return@change Outcome(config, LOCKED)
        val updated = config.schedules.map { if (it.id == id) it.copy(enabled = enabled) else it }
        val pending = if (enabled) config.pending else config.pending.filterNot { it.scheduleId == id }
        Outcome(config.copy(schedules = updated, pending = pending))
    }

    /** Unlock by waiting, X: a longer wait is stricter. */
    fun setUnlockWait(minutes: Int) = change { config, now ->
        val withoutWaiting = config.copy(pending = config.pending.filter { it.unlockWaitMinutes == null })
        if (minutes >= config.unlockWaitMinutes) Outcome(withoutWaiting.copy(unlockWaitMinutes = minutes))
        else easier(withoutWaiting, now, Pending(applyAt = cooldownEnd(now), unlockWaitMinutes = minutes))
    }

    /** Unlock by waiting, Y: a shorter pause is stricter. */
    fun setWaitPause(minutes: Int) = change { config, now ->
        val withoutWaiting = config.copy(pending = config.pending.filter { it.waitPauseMinutes == null })
        if (minutes <= config.waitPauseMinutes) Outcome(withoutWaiting.copy(waitPauseMinutes = minutes))
        else easier(withoutWaiting, now, Pending(applyAt = cooldownEnd(now), waitPauseMinutes = minutes))
    }

    /** Calling off a waiting change keeps things as strict as they are, so it always works. */
    fun cancelPending(change: Pending) = change { config, _ -> Outcome(config.copy(pending = config.pending - change), "Cancelled.") }

    /** After typing the text: a [Durations.PAUSE_MINUTES]-minute pause. */
    fun pauseAfterTyping() = pause { Durations.PAUSE_MINUTES }

    /** After waiting it out: a pause of the length set for the wait path. */
    fun pauseAfterWaiting() = pause { it.waitPauseMinutes }

    /**
     * Blocking stops for [minutesOf] the saved config, or until the block ends if that's sooner, so the pause
     * can't carry over into the next block.
     */
    private fun pause(minutesOf: (Config) -> Int) = change { config, now ->
        // Only while actually blocking: never stacks onto, or replaces, a pause already running.
        if (!config.isBlocking(now)) return@change Outcome(config)
        val short = now.plus(Duration.ofMillis(Durations.minutes(minutesOf(config))))
        val until = config.blockEndsAt(now)?.let { minOf(it, short) } ?: short
        Outcome(config.copy(pausedUntil = until), after = { Pauses.show(getApplication(), until) })
    }

    /**
     * After the unlock screen: "Block now" goes off, and running schedules pause until they end, at most
     * a day (schedules that touch end to end could otherwise run on for weeks).
     */
    fun pauseRestOfBlock() = change { config, now ->
        if (!config.isBlockTime(now)) return@change Outcome(config)
        val withoutManual = config.copy(manualBlock = false)
        val until = withoutManual.blockEndsAt(now)?.let { minOf(it, now.plusDays(1)) }
        Outcome(
            withoutManual.copy(pausedUntil = until ?: config.pausedUntil),
            after = { if (until != null) Pauses.show(getApplication(), until) else Pauses.cancel(getApplication()) },
        )
    }

    /** Ends a pause early: stricter, so always allowed. */
    fun endPause() = change { config, _ ->
        Outcome(config.copy(pausedUntil = null), after = { Pauses.cancel(getApplication()) })
    }

    private fun cooldownEnd(now: LocalDateTime): LocalDateTime = now.plus(Duration.ofMillis(Durations.COOLDOWN_MS))

    /** A change that makes NightLock easier: refused during a block, otherwise queued for 24 hours. */
    private fun easier(config: Config, now: LocalDateTime, waiting: Pending): Outcome =
        if (config.isBlockTime(now)) {
            Outcome(config, LOCKED)
        } else {
            val whenText = getApplication<Application>().formatWhen(waiting.applyAt, now)
            Outcome(config.copy(pending = config.pending + waiting), "That makes NightLock easier, so it applies in 24 h ($whenText).")
        }

    private class Outcome(val config: Config, val message: String? = null, val after: (() -> Unit)? = null)

    private companion object {
        const val LOCKED = "Locked while a block is on. Unlocking doesn't change settings either."
    }

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)

    /** Short messages for the user, shown at the bottom of the screen in full (toasts cut off after 2 lines). */
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    /** Runs [decide] on the latest saved config in one atomic step, then shows its message. */
    private fun change(decide: (Config, LocalDateTime) -> Outcome) {
        viewModelScope.launch {
            var outcome: Outcome? = null
            repository.update { config ->
                decide(config, LocalDateTime.now()).also { outcome = it }.config
            }
            outcome?.after?.invoke()
            outcome?.message?.let { _messages.tryEmit(it) }
        }
    }
}
