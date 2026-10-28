package com.orirose.nightlock.ui

import android.app.TimePickerDialog
import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.orirose.nightlock.Durations
import com.orirose.nightlock.data.Schedule
import com.orirose.nightlock.dayName
import com.orirose.nightlock.formatMinuteOfDay
import com.orirose.nightlock.weekOrder

private val WARNING_CHOICES = listOf(0, 5, 10, 15, 30)

/**
 * Edits a copy of a schedule; nothing is saved until Save.
 *
 * @param ownListCount how many apps the schedule's own list blocks once the allowlist is taken out.
 */
@Composable
fun ScheduleScreen(
    draft: Schedule,
    isNew: Boolean,
    mainListCount: Int,
    ownListCount: Int,
    notificationsAllowed: Boolean,
    onChange: (Schedule) -> Unit,
    onChooseApps: () -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var confirmDelete by remember { mutableStateOf(false) }

    fun pickTime(minute: Int, onPicked: (Int) -> Unit) {
        TimePickerDialog(context, { _, h, m -> onPicked(h * 60 + m) }, minute / 60, minute % 60, DateFormat.is24HourFormat(context)).show()
    }

    Surface(Modifier.fillMaxSize()) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("← Back") }
                Text(if (isNew) "New schedule" else "Edit schedule", style = MaterialTheme.typography.titleLarge)
            }

            OutlinedTextField(
                value = draft.name,
                onValueChange = { onChange(draft.copy(name = it.take(MAX_NAME))) },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Label("Days it starts on")
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                weekOrder().forEach { day ->
                    Toggle(dayName(day), selected = day in draft.days, modifier = Modifier.weight(1f)) {
                        onChange(draft.copy(days = if (day in draft.days) draft.days - day else draft.days + day))
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { pickTime(draft.startMinute) { onChange(draft.copy(startMinute = it)) } }, modifier = Modifier.weight(1f)) {
                    Text("Starts ${context.formatMinuteOfDay(draft.startMinute)}")
                }
                OutlinedButton(onClick = { pickTime(draft.endMinute) { onChange(draft.copy(endMinute = it)) } }, modifier = Modifier.weight(1f)) {
                    Text("Ends ${context.formatMinuteOfDay(draft.endMinute)}" + if (draft.crossesMidnight) " (next day)" else "")
                }
            }

            Label("Wind-down warning")
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                WARNING_CHOICES.forEach { minutes ->
                    Toggle(if (minutes == 0) "Off" else "$minutes ${Durations.minuteUnit}", selected = draft.warningMinutes == minutes, modifier = Modifier.weight(1f)) {
                        onChange(draft.copy(warningMinutes = minutes))
                    }
                }
            }
            if (draft.warningMinutes > 0 && !notificationsAllowed) {
                Hint("Notifications are off, so the warning won't show. Turn them on in the setup checklist.")
            }

            Label("Apps to block")
            Choice("The main block list ($mainListCount apps)", selected = draft.packages == null) {
                onChange(draft.copy(packages = null))
            }
            Choice("Its own list ($ownListCount apps)", selected = draft.packages != null) {
                onChange(draft.copy(packages = draft.packages ?: emptySet()))
            }
            if (draft.packages != null) {
                OutlinedButton(onClick = onChooseApps) { Text("Choose apps for this schedule") }
            }

            val appsMissing = if (draft.packages == null) mainListCount == 0 else ownListCount == 0
            when {
                draft.days.isEmpty() -> Hint("Pick at least one day.")
                draft.startMinute == draft.endMinute -> Hint("Start and end can't be the same time.")
                draft.name.isBlank() -> Hint("Give it a name.")
                appsMissing -> Hint("Pick at least one app to block.")
            }
            Button(onClick = onSave, enabled = draft.isValid && draft.name.isNotBlank() && !appsMissing, modifier = Modifier.fillMaxWidth()) {
                Text("Save")
            }
            if (!isNew) {
                OutlinedButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) { Text("Delete schedule") }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete \"${draft.name}\"?") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

private const val MAX_NAME = 40

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
}

@Composable
private fun Toggle(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) colors.primary else colors.surfaceContainerHighest)
            .selectable(selected = selected, role = Role.Checkbox, onClick = onClick)
            .padding(vertical = 10.dp),
    ) {
        Text(
            text,
            color = if (selected) colors.onPrimary else colors.onSurface,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Clip,
        )
    }
}

@Composable
private fun Choice(text: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(text, modifier = Modifier.padding(start = 8.dp))
    }
}
