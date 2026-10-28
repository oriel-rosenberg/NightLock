package com.orirose.nightlock

import android.app.Application
import android.content.Context
import com.orirose.nightlock.data.ConfigRepository

/** Holds the app-wide singletons. */
class NightLockApp : Application() {
    val configRepository by lazy { ConfigRepository(this) }
}

val Context.configRepository: ConfigRepository
    get() = (applicationContext as NightLockApp).configRepository

/** Tag for every NightLock log line: `adb logcat -s NightLock` shows them all. */
const val LOG_TAG = "NightLock"
