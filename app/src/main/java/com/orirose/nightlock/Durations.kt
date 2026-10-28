package com.orirose.nightlock

/**
 * Every user-visible duration goes through here, so a TEST_MODE build (-Pnightlock.testMode=true)
 * turns minutes into seconds. Clock times (a schedule's 23:30) are never scaled.
 */
object Durations {
    private val MINUTE_MS: Long = if (BuildConfig.TEST_MODE) 1_000L else 60_000L

    fun minutes(n: Int): Long = n * MINUTE_MS

    /** How long a change that makes NightLock easier waits: 24 hours, or one minute in TEST_MODE. */
    val COOLDOWN_MS: Long = if (BuildConfig.TEST_MODE) 60_000L else 24 * 60 * 60_000L

    /** The length of a short pause from the unlock screen. */
    const val PAUSE_MINUTES = 30

    /** The unit to show next to a number of "minutes": "s" in TEST_MODE. */
    val minuteUnit: String = if (BuildConfig.TEST_MODE) "s" else "min"
}
