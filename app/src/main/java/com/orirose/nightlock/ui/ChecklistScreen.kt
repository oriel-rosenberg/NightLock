package com.orirose.nightlock.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.orirose.nightlock.block.BlockerService
import com.orirose.nightlock.data.isSystemApp
import com.orirose.nightlock.data.queryActivities

/** What the setup checklist checks. Samsung's "Never sleeping apps" can't be read, so it isn't counted. */
data class SetupState(val service: Boolean, val notifications: Boolean, val battery: Boolean) {
    val done: Int get() = listOf(service, notifications, battery).count { it }
    val total: Int get() = 3
}

/** Read again each time the app comes back to the front, e.g. from Settings. */
@Composable
fun rememberSetupState(serviceRunning: Boolean): SetupState {
    val context = LocalContext.current
    var resumes by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumes++ }
    return remember(serviceRunning, resumes) {
        SetupState(
            service = serviceRunning,
            notifications = NotificationManagerCompat.from(context).areNotificationsEnabled(),
            battery = context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName),
        )
    }
}

@Composable
fun ChecklistScreen(setup: SetupState, onBack: () -> Unit) {
    val context = LocalContext.current
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        // Refused before, so Android no longer asks: send the user to the switch instead.
        if (!granted) context.openNotificationSettings()
    }
    Surface(Modifier.fillMaxSize()) {
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("← Back") }
                Text("Setup checklist", style = MaterialTheme.typography.titleLarge)
            }

            Step("Blocker service", done = setup.service, required = true) {
                Text(
                    "Lets NightLock see which app is open. Open Accessibility settings, find \"NightLock blocker\" " +
                        "(on Samsung: under Installed apps) and turn it on.",
                )
                Button(onClick = { context.openAccessibilitySettings() }) { Text("Open Accessibility settings") }
                Text(
                    "Switch greyed out, or a \"Restricted setting\" message? Open App info, tap ⋮ at the top right, " +
                        "choose \"Allow restricted settings\", then try again.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(onClick = { context.openAppInfo() }) { Text("Open App info") }
            }

            Step("Notifications", done = setup.notifications, required = false) {
                Text("Needed only for the wind-down warning before a schedule starts.")
                Button(onClick = {
                    if (Build.VERSION.SDK_INT >= 33) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else context.openNotificationSettings()
                }) { Text("Allow notifications") }
            }

            Step("Battery: Unrestricted", done = setup.battery, required = false) {
                Text(
                    "Samsung can stop apps in the background to save battery. Open App info → Battery → " +
                        "Unrestricted. This costs no battery by itself: NightLock does nothing until the screen changes.",
                )
                Button(onClick = { context.openAppInfo() }) { Text("Open App info") }
            }

            Step("Samsung: Never sleeping apps", done = null, required = false) {
                Text(
                    "Settings → Battery → Background usage limits → Never sleeping apps → + → NightLock. " +
                        "Android doesn't let apps check this one, so it has no tick.",
                )
                OutlinedButton(onClick = { context.startSystemScreen(Intent(Intent.ACTION_POWER_USAGE_SUMMARY)) }) {
                    Text("Open Battery settings")
                }
            }

            Text(
                "Not needed, by design: usage access, display over other apps, exact alarms, device admin, " +
                    "or starting at boot (Android starts the blocker service itself).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** @param done null when Android can't tell. */
@Composable
private fun Step(title: String, done: Boolean?, required: Boolean, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(16.dp)) {
            val mark = when (done) {
                true -> "✓ "
                false -> "○ "
                null -> ""
            }
            Text(mark + title + if (required) "" else "  (recommended)", style = MaterialTheme.typography.titleMedium)
            if (done != true) content()
        }
    }
}

/**
 * Opens Accessibility settings. The extras ask Settings to scroll to and highlight NightLock's entry;
 * Pixel's Settings honours them, others ignore them. (The page for one service is a system-only API.)
 */
internal fun Context.openAccessibilitySettings() {
    val service = ComponentName(this, BlockerService::class.java).flattenToString()
    startSystemScreen(
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .putExtra(":settings:fragment_args_key", service)
            .putExtra(":settings:show_fragment_args", Bundle().apply { putString(":settings:fragment_args_key", service) }),
    )
}

internal fun Context.openAppInfo() {
    startSystemScreen(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
}

internal fun Context.openNotificationSettings() {
    startSystemScreen(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
}

/**
 * Addresses the intent to the preinstalled Settings app, so another app can't pose as the settings page.
 * If no preinstalled app can show it, nothing opens.
 */
internal fun Context.startSystemScreen(intent: Intent) {
    val handlers = packageManager.queryActivities(intent).map { it.activityInfo.packageName }.filter { packageManager.isSystemApp(it) }
    val target = handlers.firstOrNull { it == "com.android.settings" } ?: handlers.firstOrNull() ?: return
    try {
        startActivity(intent.setPackage(target))
    } catch (_: ActivityNotFoundException) {
        // Disabled or removed since the lookup; nothing to open.
    }
}
