package com.orirose.nightlock

import android.app.Application
import android.content.Context
import com.orirose.nightlock.data.ConfigRepository
import com.orirose.nightlock.data.UsageRepository

/** Holds the app-wide singletons. */
class NightLockApp : Application() {
    val configRepository by lazy { ConfigRepository(this) }
    val usageRepository by lazy { UsageRepository(this) }
}

val Context.configRepository: ConfigRepository
    get() = (applicationContext as NightLockApp).configRepository

val Context.usageRepository: UsageRepository
    get() = (applicationContext as NightLockApp).usageRepository

/** Tag for every NightLock log line: `adb logcat -s NightLock` shows them all. */
const val LOG_TAG = "NightLock"
