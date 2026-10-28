package com.orirose.nightlock

/**
 * Every user-visible duration goes through here, so a TEST_MODE build (-Pnightlock.testMode=true)
 * turns minutes into seconds. Clock times (a schedule's 23:30) are never scaled.
 */
object Durations {
    private val MINUTE_MS: Long = if (BuildConfig.TEST_MODE) 1_000L else 60_000L

    fun minutes(n: Int): Long = n * MINUTE_MS

    /** The unit to show next to a number of "minutes": "s" in TEST_MODE. */
    val minuteUnit: String = if (BuildConfig.TEST_MODE) "s" else "min"
}
