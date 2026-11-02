package com.orirose.nightlock.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.orirose.nightlock.BuildConfig
import com.orirose.nightlock.Durations
import com.orirose.nightlock.data.Config
import com.orirose.nightlock.data.DayUsage
import com.orirose.nightlock.data.LIMIT_DAY_START_HOUR
import com.orirose.nightlock.data.Pending
import com.orirose.nightlock.data.Schedule
import com.orirose.nightlock.data.TimeLimit
import com.orirose.nightlock.data.limitDay
import com.orirose.nightlock.describeDays
import com.orirose.nightlock.formatDuration
import com.orirose.nightlock.formatMinuteOfDay
import com.orirose.nightlock.formatTime
import com.orirose.nightlock.formatWhen
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

private val WAIT_CHOICES = listOf(1, 2, 3, 5, 10, 15, 30)
private val WAIT_PAUSE_CHOICES = listOf(5, 10, 15, 20, 30, 45, 60)

@Composable
fun HomeScreen(
    config: Config,
    now: LocalDateTime,
    usage: DayUsage,
    blockedCount: Int,
    allowedCount: Int?,
    setup: SetupState,
    labelOf: (packageName: String) -> String,
    onManualBlock: (Boolean) -> Unit,
    onUnlock: () -> Unit,
    onEndPause: () -> Unit,
    onScheduleEnabled: (id: String, enabled: Boolean) -> Unit,
    onEditSchedule: (Schedule) -> Unit,
    onAddSchedule: () -> Unit,
    onEditLimit: (TimeLimit) -> Unit,
    onAddLimit: () -> Unit,
    onUnlockWait: (Int) -> Unit,
    onWaitPause: (Int) -> Unit,
    onCancelPending: (Pending) -> Unit,
    onOpenBlockList: () -> Unit,
    onOpenAllowlist: () -> Unit,
    onOpenChecklist: () -> Unit,
    onRestoreBackup: (Uri) -> Unit,
) {
    val blockTime = config.isBlockTime(now)
    val pickBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(onRestoreBackup) }
    Surface(Modifier.fillMaxSize()) {
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        ) {
            Text("NightLock", style = MaterialTheme.typography.headlineMedium)

            if (BuildConfig.TEST_MODE) {
                SectionCard(MaterialTheme.colorScheme.tertiaryContainer) {
                    Text("TEST MODE", style = MaterialTheme.typography.titleMedium)
                    Text("Waits, warnings and pauses are in seconds instead of minutes; the 24-hour wait is one minute.")
                }
            }

            if (config.isUntouched) {
                SectionCard {
                    Text("Installed NightLock again?", style = MaterialTheme.typography.titleMedium)
                    Text("If you kept a settings backup, pick the file to get everything back.")
                    OutlinedButton(onClick = { pickBackup.launch(arrayOf("*/*")) }) { Text("Restore settings") }
                }
            }

            StatusCard(config, now, usage, setup.service, labelOf, onUnlock, onEndPause)

            if (setup.done < setup.total) {
                SectionCard(if (setup.service) Color.Unspecified else MaterialTheme.colorScheme.tertiaryContainer) {
                    Text("Setup: ${setup.done} of ${setup.total} done", style = MaterialTheme.typography.titleMedium)
                    Button(onClick = onOpenChecklist) { Text("Open setup checklist") }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("✓ Setup complete", modifier = Modifier.weight(1f))
                    TextButton(onClick = onOpenChecklist) { Text("Checklist") }
                }
            }

            if (config.pending.isNotEmpty()) {
                SectionCard {
                    Text("Waiting 24 hours", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Changes that make NightLock easier apply a day after you make them.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    config.pending.sortedBy { it.applyAt }.forEach { change ->
                        PendingRow(describe(change, config, labelOf), LocalContext.current.formatWhen(change.applyAt, now)) {
                            onCancelPending(change)
                        }
                    }
                }
            }

            SectionCard(if (config.manualBlocking) MaterialTheme.colorScheme.primaryContainer else Color.Unspecified) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Block now", style = MaterialTheme.typography.titleMedium)
                        Text(
                            when {
                                config.manualBlock && blockedCount == 0 -> "On, but no apps are chosen yet: choose them under Blocked apps."
                                config.manualBlocking -> "On until you switch it off with Unlock."
                                else -> "Blocks the main block list until you switch it off with Unlock."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Switch(checked = config.manualBlock, onCheckedChange = onManualBlock, enabled = !config.manualBlocking)
                }
            }

            SectionCard {
                Text("Schedules", style = MaterialTheme.typography.titleMedium)
                if (config.schedules.isEmpty()) Text("None yet.")
                config.schedules.forEach { schedule ->
                    ScheduleRow(
                        schedule,
                        hasApps = config.appsOf(schedule).isNotEmpty(),
                        onEnabled = { onScheduleEnabled(schedule.id, it) },
                        onClick = { onEditSchedule(schedule) },
                    )
                }
                OutlinedButton(onClick = onAddSchedule) { Text("Add schedule") }
            }

            SectionCard {
                Text("Daily limits", style = MaterialTheme.typography.titleMedium)
                if (config.limits.isEmpty()) Text("None yet. Limit one app, or a group of apps that share the time.")
                val today = usage.on(limitDay(now))
                config.limits.forEach { limit -> LimitRow(limit, today, labelOf, onClick = { onEditLimit(limit) }) }
                OutlinedButton(onClick = onAddLimit) { Text("Add limit") }
            }

            SectionCard {
                Text("Blocked apps", style = MaterialTheme.typography.titleMedium)
                Text("$blockedCount chosen. Used by Block now, and by schedules set to the main list.")
                Button(onClick = onOpenBlockList) { Text("Choose apps") }
            }

            SectionCard {
                Text("Unlock by waiting", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Wait ${config.unlockWaitMinutes} ${Durations.minuteUnit} on the unlock screen to pause blocking for " +
                        "${config.waitPauseMinutes} ${Durations.minuteUnit}. A longer wait or a shorter pause applies at once; " +
                        "the other way round takes 24 hours.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text("Wait (${Durations.minuteUnit})", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    WAIT_CHOICES.forEach { minutes ->
                        Toggle("$minutes", selected = config.unlockWaitMinutes == minutes, modifier = Modifier.weight(1f)) {
                            onUnlockWait(minutes)
                        }
                    }
                }
                Text("Pause it earns (${Durations.minuteUnit})", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    WAIT_PAUSE_CHOICES.forEach { minutes ->
                        Toggle("$minutes", selected = config.waitPauseMinutes == minutes, modifier = Modifier.weight(1f)) {
                            onWaitPause(minutes)
                        }
                    }
                }
            }

            SectionCard {
                Text("Always allowed", style = MaterialTheme.typography.titleMedium)
                Text("${allowedCount ?: "…"} apps that are never blocked: phone, messages, clock, Settings, maps and any you add.")
                Button(onClick = onOpenAllowlist, enabled = !blockTime) { Text("Edit allowlist") }
                if (blockTime) {
                    Text(
                        "Locked while a block is on.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusCard(
    config: Config,
    now: LocalDateTime,
    usage: DayUsage,
    serviceRunning: Boolean,
    labelOf: (String) -> String,
    onUnlock: () -> Unit,
    onEndPause: () -> Unit,
) {
    val context = LocalContext.current
    val blocking = config.isBlocking(now, usage)
    val paused = config.hasPausedBlock(now, usage)
    SectionCard(if (blocking) MaterialTheme.colorScheme.primaryContainer else Color.Unspecified) {
        when {
            blocking -> {
                Text("Blocking now", style = MaterialTheme.typography.titleMedium)
                Text(blockDescription(config, now, usage, context, labelOf = labelOf))
                Button(onClick = onUnlock) { Text("Unlock…") }
            }
            paused -> Text("Paused", style = MaterialTheme.typography.titleMedium)
            else -> {
                Text("Not blocking", style = MaterialTheme.typography.titleMedium)
                Text(
                    config.nextBlockStart(now)?.let { "Next: ${it.schedule.name}, ${context.formatWhen(it.start, now)}" }
                        ?: "No schedule coming up.",
                )
            }
        }
        if (paused) {
            config.pauseEndsAt(now)?.let { Text("A pause ends at ${context.formatTime(it.toLocalTime())}.") }
            OutlinedButton(onClick = onEndPause) { Text("End pause now") }
        }
        if (!serviceRunning) {
            Text(
                "The blocker service is off, so nothing is actually blocked. See the setup checklist.",
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

private fun describe(change: Pending, config: Config, labelOf: (String) -> String): String {
    val scheduleName = change.scheduleId?.let { id -> config.schedules.firstOrNull { it.id == id }?.name } ?: "a schedule"
    val limitName = change.limitId?.let { id -> config.limits.firstOrNull { it.id == id }?.displayName(labelOf) } ?: "a limit"
    return when {
        change.deleteLimit != null -> "Delete limit: $limitName"
        change.limit != null -> "Change limit: $limitName"
        change.unblock != null -> "Unblock ${labelOf(change.unblock)}"
        change.allow != null -> "Always allow ${labelOf(change.allow)}"
        change.deleteSchedule != null -> "Delete $scheduleName"
        change.schedule != null -> if (!change.schedule.enabled) "Switch off $scheduleName" else "Change $scheduleName"
        change.unlockWaitMinutes != null -> "Unlock wait: ${change.unlockWaitMinutes} ${Durations.minuteUnit}"
        change.waitPauseMinutes != null -> "Pause after waiting: ${change.waitPauseMinutes} ${Durations.minuteUnit}"
        else -> "Change"
    }
}

@Composable
private fun PendingRow(what: String, applies: String, onCancel: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(what, style = MaterialTheme.typography.bodyLarge)
            Text("applies $applies", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = onCancel) { Text("Cancel") }
    }
}

/**
 * Why [packageName] (or anything, if null) is blocked, one line per reason: a schedule, the manual switch, or
 * daily limits. Shared with the block screen.
 */
fun blockDescription(
    config: Config,
    now: LocalDateTime,
    usage: DayUsage,
    context: android.content.Context,
    packageName: String? = null,
    labelOf: (String) -> String,
): String {
    val limits = config.limitsBlocking(now, usage).filter { packageName == null || packageName in it.packages }
    return listOfNotNull(
        if (config.schedulesBlocking(now)) scheduleDescription(config, now, context, packageName) else null,
        if (limits.isEmpty()) null else "Daily limit reached: ${limits.joinToString(", ") { it.displayName(labelOf) }}. " +
            "Starts over at ${context.formatTime(LocalTime.of(LIMIT_DAY_START_HOUR, 0))}.",
    ).joinToString("\n")
}

/** "Bedtime · ends 07:00 (in 6 h 12 min)", the manual switch, or null if neither covers [packageName]. */
private fun scheduleDescription(config: Config, now: LocalDateTime, context: android.content.Context, packageName: String?): String? {
    if (config.manualBlocking && (packageName == null || packageName in config.blockedPackages)) {
        return "\"Block now\" is on."
    }
    val names = config.activeScheduleNames(now, packageName).joinToString(", ").ifEmpty { return null }
    val end = config.blockEndsAt(now, packageName) ?: return names
    // Through time zones, so a daylight-saving night counts its real length.
    val zone = ZoneId.systemDefault()
    val left = Duration.between(now.atZone(zone), end.atZone(zone))
    return "$names · ends ${context.formatWhen(end, now)} (in ${formatDuration(left)})"
}

@Composable
private fun LimitRow(limit: TimeLimit, today: DayUsage, labelOf: (String) -> String, onClick: () -> Unit) {
    val used = today.of(limit)
    val reached = used >= limit.allowanceMs
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp)) {
        Text(limit.displayName(labelOf), style = MaterialTheme.typography.bodyLarge)
        Text(
            if (reached) "Limit reached: ${limit.minutes} ${Durations.minuteUnit}"
            else "${used / Durations.minutes(1)} of ${limit.minutes} ${Durations.minuteUnit} used today" +
                if (limit.packages.size > 1) " · ${limit.packages.size} apps" else "",
            style = MaterialTheme.typography.bodySmall,
            color = if (reached) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ScheduleRow(schedule: Schedule, hasApps: Boolean, onEnabled: (Boolean) -> Unit, onClick: () -> Unit) {
    val context = LocalContext.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(schedule.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                "${context.formatMinuteOfDay(schedule.startMinute)}–${context.formatMinuteOfDay(schedule.endMinute)} · " +
                    describeDays(schedule.days),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!hasApps) {
                Text("No apps to block, so it won't run", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
        Switch(checked = schedule.enabled, onCheckedChange = onEnabled)
    }
}

@Composable
internal fun SectionCard(color: Color = Color.Unspecified, content: @Composable ColumnScope.() -> Unit) {
    val colors = if (color == Color.Unspecified) CardDefaults.cardColors() else CardDefaults.cardColors(containerColor = color)
    Card(colors = colors, modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(16.dp), content = content)
    }
}
