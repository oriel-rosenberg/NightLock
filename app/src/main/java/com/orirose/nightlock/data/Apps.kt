package com.orirose.nightlock.data

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.provider.AlarmClock
import android.provider.Settings
import android.provider.Telephony
import android.telecom.TelecomManager
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap

/** Apps that keep the phone usable and safe at night. */
class Essentials(
    /**
     * Never blocked, and can't be taken off the allowlist: NightLock, the home screen, System UI,
     * the phone and in-call screens, the clock (its alarm screen must show), and Settings.
     */
    val locked: Set<String>,
    /** What the allowlist starts with: [locked] plus the messages and maps apps. */
    val defaultAllowlist: Set<String>,
) {
    companion object {
        // Known package names, for when the phone has more than one, or none is set as default.
        private val PHONE_PACKAGES = setOf(
            "com.android.phone", "com.android.server.telecom", "com.android.dialer",
            "com.google.android.dialer", "com.samsung.android.dialer", "com.samsung.android.incallui",
            "com.android.emergency",
        )
        private val CLOCK_PACKAGES = setOf(
            "com.sec.android.app.clockpackage", "com.google.android.deskclock", "com.android.deskclock",
        )
        private val MAPS_PACKAGES = setOf("com.google.android.apps.maps")

        /**
         * Only preinstalled (system) apps and the apps Android records as *your* choice count: the default
         * home screen, phone and SMS apps, and the app that set your next alarm. Any app can declare that
         * it handles "dial" or "settings", and any app can take a package name that isn't installed, so
         * trusting those would let an app make itself unblockable.
         *
         * Defaults can change at any time, so the blocker calls this again right before it blocks anything.
         */
        fun detect(context: Context): Essentials {
            val pm = context.packageManager
            fun systemHandlers(intent: Intent) =
                pm.queryActivities(intent).map { it.activityInfo.packageName }.filter { pm.isSystemApp(it) }

            val locked = buildSet {
                add(context.packageName)
                add("com.android.systemui")
                addAll(systemHandlers(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)))
                // The home screen in use must never be blocked, or leaving the block screen would loop.
                // With more than one and none chosen this is the "open with" chooser ("android"), skipped.
                pm.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY)
                    ?.activityInfo?.packageName?.takeUnless { it == "android" }?.let(::add)
                context.getSystemService(TelecomManager::class.java)?.defaultDialerPackage?.let(::add)
                addAll(systemHandlers(Intent(Intent.ACTION_DIAL)))
                addAll(PHONE_PACKAGES.filter { pm.isSystemApp(it) })
                addAll(systemHandlers(Intent(AlarmClock.ACTION_SHOW_ALARMS)))
                addAll(CLOCK_PACKAGES.filter { pm.isSystemApp(it) })
                // A third-party alarm app: its ringing screen must show. Only an app with a real upcoming
                // alarm (shown in the status bar) counts.
                context.getSystemService(AlarmManager::class.java)?.nextAlarmClock?.showIntent?.creatorPackage?.let(::add)
                addAll(systemHandlers(Intent(Settings.ACTION_SETTINGS)))
            }
            val extras = buildSet {
                Telephony.Sms.getDefaultSmsPackage(context)?.let(::add)
                addAll(systemHandlers(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:"))))
                addAll(systemHandlers(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q="))))
                addAll(MAPS_PACKAGES.filter { pm.isSystemApp(it) })
            }
            return Essentials(locked, locked + extras)
        }
    }
}

/** An app as shown in the pickers and on the block screen. */
class AppEntry(val packageName: String, val label: String, val icon: ImageBitmap)

private const val ICON_PX = 96

/** Every app with a launcher icon, sorted by name. Slow (loads icons): call off the main thread. */
fun loadLaunchableApps(context: Context): List<AppEntry> {
    val pm = context.packageManager
    return pm.queryActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER))
        .distinctBy { it.activityInfo.packageName }
        .map { info ->
            AppEntry(
                packageName = info.activityInfo.packageName,
                label = info.loadLabel(pm).toString(),
                icon = info.loadIcon(pm).toBitmap(ICON_PX, ICON_PX).asImageBitmap(),
            )
        }
        .sortedBy { it.label.lowercase() }
}

/** One app's name and icon, or null if it isn't installed. */
fun loadApp(context: Context, packageName: String): AppEntry? = runCatching {
    val pm = context.packageManager
    val info = pm.getApplicationInfo(packageName, 0)
    AppEntry(packageName, pm.getApplicationLabel(info).toString(), pm.getApplicationIcon(info).toBitmap(ICON_PX, ICON_PX).asImageBitmap())
}.getOrNull()

@Suppress("DEPRECATION") // The flags-object overload is API 33+; this one still works everywhere.
internal fun PackageManager.queryActivities(intent: Intent): List<ResolveInfo> = queryIntentActivities(intent, 0)

/** Preinstalled with the phone (including updated system apps), as opposed to installed by the user. */
@Suppress("DEPRECATION")
internal fun PackageManager.isSystemApp(packageName: String): Boolean = runCatching {
    getApplicationInfo(packageName, 0).flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
}.getOrDefault(false)
