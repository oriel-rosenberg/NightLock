package com.orirose.nightlock.block

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.content.ContextCompat
import com.orirose.nightlock.LOG_TAG
import com.orirose.nightlock.configRepository
import com.orirose.nightlock.data.Config
import com.orirose.nightlock.data.DayUsage
import com.orirose.nightlock.data.Essentials
import com.orirose.nightlock.usageRepository
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Watches which app is on screen and covers blocked apps with [BlockActivity].
 *
 * The system keeps this service bound (and starts it again after a reboot), which also lets it start
 * activities from the background. Nothing runs between events except one timer for the next change (a
 * schedule, the end of a pause, limits starting over, or the app on screen using up its daily limit), which only
 * advances while the phone is awake: when it's asleep no one is using an app, and waking it up (screen on)
 * re-checks the clock.
 *
 * Daily limits are counted here too, from the same window changes, with no polling: time is added up when
 * a limited app leaves the screen, the screen goes off, or the timer fires.
 */
class BlockerService : AccessibilityService() {
    private val scope = MainScope()
    private val handler = Handler(Looper.getMainLooper())
    private var config = Config()
    private var essentials: Essentials? = null
    private var rules = BlockRules.OFF

    /** [rules] hold until this moment (the next schedule change); null means until the config changes. */
    private var rulesValidUntil: LocalDateTime? = null

    /** When [rules] were worked out; the wall clock going back past it (clocks going back) means work them out again. */
    private var rulesComputedAt: LocalDateTime = LocalDateTime.MIN

    /**
     * Window id → package. Reading a window's package means a call into that app, which can stall
     * if the app is busy; a window never changes app, so each one is read only once.
     */
    private val windowPackages = HashMap<Int, String>()

    /** Window id → when to try again: a window that couldn't be read isn't asked again on every event. */
    private val unreadableUntil = HashMap<Int, Long>()

    /** Warnings already shown, by schedule id and start time, so each shows once. */
    private val warned = HashSet<Pair<String, LocalDateTime>>()

    /** The limits (by id) with an app in use right now; their time runs from [countingSince]. */
    private var counting: Set<String> = emptySet()
    private var countingSince = 0L

    /** Set once today's saved counts are in: before that, counting would overwrite them with nothing. */
    private var usageLoaded = false

    private val power by lazy { getSystemService(PowerManager::class.java) }
    private val keyguard by lazy { getSystemService(KeyguardManager::class.java) }

    /**
     * Runs a moment after the block screen was launched. If a blocked app is still visible then
     * (split screen, a pop-up window, or the block screen failed to open), go to the home screen.
     */
    private val recheck = Runnable {
        recheckPending = false
        visibleBlockedPackage()?.let {
            Log.w(LOG_TAG, "$it still visible after the block screen opened; going home")
            performGlobalAction(GLOBAL_ACTION_HOME)
        }
    }
    private var recheckPending = false

    private var lastLogged: String? = null
    private var lastLoggedAt = 0L

    /** Fires at the next change: a block starting or ending, a warning, a limit running out, limits starting over. */
    private val clockTick = Runnable { onClock() }

    /**
     * A second look shortly after a block starts or an app opens: an app's window can be briefly unreadable,
     * and while someone just sits in an app no new window events arrive to catch it later.
     */
    private val lateCheck = Runnable {
        trackLimits(LocalDateTime.now())
        visibleBlockedPackage()?.let(::showBlockScreen)
    }

    /**
     * Screen on, and the clock or time zone changed: the timer may be off, so check now. Screen off and
     * the phone being unlocked: time on a limit stops and starts.
     */
    private val clockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = onClock()
    }

    override fun onServiceConnected() {
        _running.value = true
        Log.i(LOG_TAG, "Blocker service connected")
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        ContextCompat.registerReceiver(this, clockReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        scope.launch {
            essentials = withContext(Dispatchers.Default) { Essentials.detect(this@BlockerService) }
            _usage.value = usageRepository.load()
            usageLoaded = true
            configRepository.config
                // A failed read keeps the last good config and tries again, waiting longer each time.
                .retryWhen { cause, attempt ->
                    Log.e(LOG_TAG, "Couldn't read settings; retrying", cause)
                    delay(SETTINGS_RETRY_MS * (attempt + 1).coerceAtMost(12))
                    true
                }
                .collect {
                    config = it
                    onClock()
                }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val now = LocalDateTime.now()
        if (config.limits.isNotEmpty()) {
            trackLimits(now)
            handler.removeCallbacks(lateCheck)
            handler.postDelayed(lateCheck, RECHECK_DELAY_MS)
        }
        if (rulesValidUntil?.let { now >= it } == true || now < rulesComputedAt) refreshRules(now)
        if (!rules.active) return
        // The event's own package is checked first: the window list can lag a little behind it.
        val eventPackage = event.packageName?.toString()?.takeIf {
            event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && rules.blocks(it) && !isPictureInPicture(event.windowId)
        }
        (eventPackage ?: visibleBlockedPackage())?.let(::showBlockScreen)
    }

    /** The limits covering an app that's being used now: on screen, with the phone awake and unlocked. */
    private fun limitsInUse(): Set<String> =
        if (config.limits.isNotEmpty() && power.isInteractive && !keyguard.isKeyguardLocked) config.limitsOn(visibleAppPackages()) else emptySet()

    /** Starts or stops counting if the limited apps in use have changed. */
    private fun trackLimits(now: LocalDateTime) {
        val inUse = limitsInUse()
        if (inUse == counting) return
        updateUsage(inUse)
        refreshRules(now)
        scheduleNextTick(now)
    }

    /** Adds the time since the last count to the limits in use, then carries on counting [inUse] from now. */
    private fun updateUsage(inUse: Set<String>) {
        if (!usageLoaded) return
        val nowMs = SystemClock.elapsedRealtime()
        val updated = _usage.value.after(counting, nowMs - countingSince, LocalDateTime.now())
        counting = inUse
        countingSince = nowMs
        if (updated != _usage.value) {
            _usage.value = updated
            scope.launch { usageRepository.save(updated) }
        }
    }

    /** Works the rules out again; they're cached until the next change, so events stay cheap. */
    private fun refreshRules(now: LocalDateTime) {
        val known = essentials ?: return
        rules = BlockRules.from(config, known, now, _usage.value)
        rulesValidUntil = config.nextChangeAfter(now)
        rulesComputedAt = now
    }

    private fun onClock() {
        val now = LocalDateTime.now()
        try {
            updateUsage(limitsInUse())
            // Changes whose 24 hours are up, and ended pauses, get saved; saving runs this again.
            val known = essentials
            if (known != null && config.applyDue(now, known, _usage.value) != config) {
                scope.launch { configRepository.update { it.applyDue(LocalDateTime.now(), known, _usage.value) } }
            }
            refreshRules(now)
            Log.i(LOG_TAG, "Clock check: blocking=${rules.active}, ${rules.blocked.size} apps")
            // A block can start while a blocked app is already open.
            visibleBlockedPackage()?.let(::showBlockScreen)
            if (rules.active) {
                handler.removeCallbacks(lateCheck)
                handler.postDelayed(lateCheck, RECHECK_DELAY_MS)
            }
            updateWarnings(now)
        } catch (e: RuntimeException) {
            Log.e(LOG_TAG, "Clock check failed", e)
        } finally {
            // Whatever happened above, the next change must still be caught.
            scheduleNextTick(now)
        }
    }

    private fun updateWarnings(now: LocalDateTime) {
        val known = essentials ?: return
        val due = config.warningsDue(now)
        val dueKeys = due.map { it.schedule.id to it.start }.toSet()
        // A warning whose schedule was switched off, deleted or moved since it was shown goes away.
        warned.filter { it !in dueKeys }.forEach { (id, start) -> if (start > now) Warnings.cancel(this, id) }
        warned.retainAll(dueKeys)
        due.forEach { occurrence ->
            if (warned.add(occurrence.schedule.id to occurrence.start)) {
                val apps = config.appsOf(occurrence.schedule) - config.allowlist(known)
                Warnings.show(this, occurrence, apps.size)
            }
        }
    }

    private fun scheduleNextTick(now: LocalDateTime) {
        handler.removeCallbacks(clockTick)
        val toChange = config.nextChangeAfter(now)?.let(::millisUntil)
        // Real time from the last count, whatever the wall clock does in between.
        val toRunOut = config.millisToRunOut(now, _usage.value, counting)?.minus(SystemClock.elapsedRealtime() - countingSince)
        val delay = listOfNotNull(toChange, toRunOut).minOrNull() ?: return
        handler.postDelayed(clockTick, delay.coerceAtLeast(MIN_TICK_MS))
    }

    /** How long until the wall clock reads [time]; zero or less if that moment has only just gone. */
    private fun millisUntil(time: LocalDateTime): Long {
        val zone = ZoneId.systemDefault()
        val nowMs = System.currentTimeMillis()
        // A wall-clock time can happen once, twice (the hour repeated when clocks go back: take the first
        // one still ahead) or never (skipped when clocks go forward: it happens the moment the clock jumps).
        val at = zone.rules.getValidOffsets(time).map { time.toInstant(it).toEpochMilli() }.filter { it > nowMs }.minOrNull()
            ?: zone.rules.getTransition(time)?.takeIf { it.isGap }?.instant?.toEpochMilli()
            ?: nowMs
        return at - nowMs
    }

    /** A blocked app with a window on screen now. */
    private fun visibleBlockedPackage(): String? =
        if (rules.active) visibleAppPackages().firstOrNull(rules::blocks) else null

    /** The apps with a window on screen now, counting both halves of split screen and pop-ups. */
    private fun visibleAppPackages(): List<String> {
        val appWindows = windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        val ids = appWindows.mapTo(HashSet()) { it.id }
        windowPackages.keys.retainAll(ids)
        unreadableUntil.keys.retainAll(ids)
        return appWindows
            // Picture-in-picture is skipped: it floats over the block screen and home screen alike,
            // so covering it would trap the user in the block screen.
            .filter { !it.isInPictureInPictureMode }
            .mapNotNull(::packageOf)
    }

    private fun packageOf(window: AccessibilityWindowInfo): String? {
        windowPackages[window.id]?.let { return it }
        val nowMs = SystemClock.elapsedRealtime()
        // With limits set, the late check after each event asks again; without them the next event has to.
        if (config.limits.isNotEmpty() && (unreadableUntil[window.id] ?: 0L) > nowMs) return null
        val packageName = window.root?.packageName?.toString()
        if (packageName != null) windowPackages[window.id] = packageName else unreadableUntil[window.id] = nowMs + UNREADABLE_RETRY_MS
        return packageName
    }

    private fun isPictureInPicture(windowId: Int): Boolean =
        windows.any { it.id == windowId && it.isInPictureInPictureMode }

    private fun showBlockScreen(packageName: String) {
        // The essentials (default home screen, phone app, next alarm's app) may have changed since the
        // rules were built. Blocking is rare, so check them again now rather than risk covering one.
        essentials = Essentials.detect(this)
        refreshRules(LocalDateTime.now())
        if (!rules.blocks(packageName)) {
            Log.i(LOG_TAG, "$packageName is not blocked after re-checking; leaving it")
            return
        }
        // One opening of an app fires several window events; log it once.
        val nowMs = SystemClock.elapsedRealtime()
        if (packageName != lastLogged || nowMs - lastLoggedAt > LOG_REPEAT_MS) {
            Log.i(LOG_TAG, "Blocked $packageName")
            lastLogged = packageName
            lastLoggedAt = nowMs
        }
        startActivity(
            Intent(this, BlockActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(BlockActivity.EXTRA_PACKAGE, packageName),
        )
        // Not restarted by later events: a stream of them must not keep postponing the check.
        if (!recheckPending) {
            recheckPending = true
            handler.postDelayed(recheck, RECHECK_DELAY_MS)
        }
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        _running.value = false
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        _running.value = false
        if (usageLoaded) {
            updateUsage(emptySet())
            val last = _usage.value
            // Not in [scope], which ends here.
            CoroutineScope(Dispatchers.IO).launch { usageRepository.save(last) }
        }
        handler.removeCallbacksAndMessages(null)
        runCatching { unregisterReceiver(clockReceiver) }
        scope.cancel()
        Log.i(LOG_TAG, "Blocker service stopped")
        super.onDestroy()
    }

    companion object {
        private const val RECHECK_DELAY_MS = 1500L
        private const val UNREADABLE_RETRY_MS = 1000L
        private const val MIN_TICK_MS = 500L
        private const val SETTINGS_RETRY_MS = 5_000L
        private const val LOG_REPEAT_MS = 2_000L

        private val _running = MutableStateFlow(false)

        /** Whether the system has the service connected right now (not just switched on in Settings). */
        val running: StateFlow<Boolean> = _running.asStateFlow()

        private val _usage = MutableStateFlow(DayUsage())

        /** Today's time on each limit, up to the last count. Read directly by the screens, in this same process. */
        val usage: StateFlow<DayUsage> = _usage.asStateFlow()
    }
}
