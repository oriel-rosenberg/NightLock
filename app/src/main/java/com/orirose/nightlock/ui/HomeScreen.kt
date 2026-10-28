package com.orirose.nightlock.ui

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
import com.orirose.nightlock.data.Config
import com.orirose.nightlock.data.Schedule
import com.orirose.nightlock.describeDays
import com.orirose.nightlock.formatDuration
import com.orirose.nightlock.formatMinuteOfDay
import com.orirose.nightlock.formatWhen
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId

@Composable
fun HomeScreen(
    config: Config,
    now: LocalDateTime,
    blockedCount: Int,
    allowedCount: Int?,
    setup: SetupState,
    onManualBlock: (Boolean) -> Unit,
    onScheduleEnabled: (id: String, enabled: Boolean) -> Unit,
    onEditSchedule: (Schedule) -> Unit,
    onAddSchedule: () -> Unit,
    onOpenBlockList: () -> Unit,
    onOpenAllowlist: () -> Unit,
    onOpenChecklist: () -> Unit,
) {
    val blocking = config.isBlocking(now)
    Surface(Modifier.fillMaxSize()) {
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        ) {
            Text("NightLock", style = MaterialTheme.typography.headlineMedium)

            if (BuildConfig.TEST_MODE) {
                SectionCard(MaterialTheme.colorScheme.tertiaryContainer) {
                    Text("TEST MODE", style = MaterialTheme.typography.titleMedium)
                    Text("Warnings, delays, pauses and cooldowns are measured in seconds instead of minutes.")
                }
            }

            StatusCard(config, now, blocking, setup.service)

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

            SectionCard(if (config.manualBlocking) MaterialTheme.colorScheme.primaryContainer else Color.Unspecified) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Block now", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (config.manualBlock && blockedCount == 0) "On, but no apps are chosen yet: choose them under Blocked apps."
                            else "Blocks the main block list until you switch it off.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Switch(checked = config.manualBlock, onCheckedChange = onManualBlock)
                }
            }

            SectionCard {
                Text("Schedules", style = MaterialTheme.typography.titleMedium)
                if (config.schedules.isEmpty()) Text("None yet.")
                config.schedules.forEach { schedule ->
                    ScheduleRow(schedule, onEnabled = { onScheduleEnabled(schedule.id, it) }, onClick = { onEditSchedule(schedule) })
                }
                OutlinedButton(onClick = onAddSchedule) { Text("Add schedule") }
            }

            SectionCard {
                Text("Blocked apps", style = MaterialTheme.typography.titleMedium)
                Text("$blockedCount chosen. Used by Block now, and by schedules set to the main list.")
                Button(onClick = onOpenBlockList) { Text("Choose apps") }
            }

            SectionCard {
                Text("Always allowed", style = MaterialTheme.typography.titleMedium)
                Text("${allowedCount ?: "…"} apps that are never blocked: phone, messages, clock, Settings, maps and any you add.")
                Button(onClick = onOpenAllowlist, enabled = !blocking) { Text("Edit allowlist") }
                if (blocking) {
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
private fun StatusCard(config: Config, now: LocalDateTime, blocking: Boolean, serviceRunning: Boolean) {
    val context = LocalContext.current
    SectionCard(if (blocking) MaterialTheme.colorScheme.primaryContainer else Color.Unspecified) {
        Text(if (blocking) "Blocking now" else "Not blocking", style = MaterialTheme.typography.titleMedium)
        val detail = if (blocking) {
            blockDescription(config, now, context)
        } else {
            config.nextBlockStart(now)?.let { "Next: ${it.schedule.name}, ${context.formatWhen(it.start, now)}" }
                ?: "No schedule coming up."
        }
        Text(detail)
        if (!serviceRunning) {
            Text(
                "The blocker service is off, so nothing is actually blocked. See the setup checklist.",
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/**
 * "Bedtime · ends 07:00 (in 6 h 12 min)", or the manual switch, for [packageName] or for any block if null.
 * Shared with the block screen.
 */
fun blockDescription(config: Config, now: LocalDateTime, context: android.content.Context, packageName: String? = null): String {
    if (config.manualBlocking && (packageName == null || packageName in config.blockedPackages)) {
        return "\"Block now\" is on: blocked until you switch it off in NightLock."
    }
    val names = config.activeScheduleNames(now, packageName).joinToString(", ")
    val end = config.blockEndsAt(now, packageName) ?: return names
    // Through time zones, so a daylight-saving night counts its real length.
    val zone = ZoneId.systemDefault()
    val left = Duration.between(now.atZone(zone), end.atZone(zone))
    return "$names · ends ${context.formatWhen(end, now)} (in ${formatDuration(left)})"
}

@Composable
private fun ScheduleRow(schedule: Schedule, onEnabled: (Boolean) -> Unit, onClick: () -> Unit) {
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
