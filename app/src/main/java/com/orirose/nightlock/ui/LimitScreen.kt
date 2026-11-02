package com.orirose.nightlock.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.orirose.nightlock.Durations
import com.orirose.nightlock.data.TimeLimit

private val MINUTE_CHOICES = listOf(15, 30, 60, 90, 120)
private const val MAX_NAME = 40

/**
 * Edits a copy of a daily limit; nothing is saved until Save.
 *
 * @param appNames the chosen apps that can be blocked, by name: allowlisted ones never are.
 */
@Composable
fun LimitScreen(
    draft: TimeLimit,
    isNew: Boolean,
    appNames: List<String>,
    onChange: (TimeLimit) -> Unit,
    onChooseApps: () -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    onBack: () -> Unit,
) {
    var confirmDelete by remember { mutableStateOf(false) }
    Surface(Modifier.fillMaxSize()) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("← Back") }
                Text(if (isNew) "New daily limit" else "Edit daily limit", style = MaterialTheme.typography.titleLarge)
            }

            Label("Apps")
            Text(
                appNames.joinToString(", ").ifEmpty { "None chosen yet." },
            )
            Text(
                "One app gets a limit of its own. Several share the time between them, on top of any limits they have of their own.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = onChooseApps) { Text("Choose apps") }

            OutlinedTextField(
                value = draft.name,
                onValueChange = { onChange(draft.copy(name = it.take(MAX_NAME))) },
                label = { Text(if (appNames.size > 1) "Group name" else "Name (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Label("Time a day (${Durations.minuteUnit})")
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                MINUTE_CHOICES.forEach { minutes ->
                    Toggle("$minutes", selected = draft.minutes == minutes, modifier = Modifier.weight(1f)) {
                        onChange(draft.copy(minutes = minutes))
                    }
                }
            }
            OutlinedTextField(
                value = if (draft.minutes == 0) "" else "${draft.minutes}",
                onValueChange = { text -> onChange(draft.copy(minutes = text.filter(Char::isDigit).take(4).toIntOrNull() ?: 0)) },
                label = { Text("Or any other number") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )

            val nameMissing = appNames.size > 1 && draft.name.isBlank()
            when {
                appNames.isEmpty() -> Hint("Pick at least one app. Always-allowed apps can't have a limit.")
                nameMissing -> Hint("Give the group a name.")
                !draft.isValid -> Hint("The time has to be under a day.")
            }
            Button(onClick = onSave, enabled = draft.isValid && appNames.isNotEmpty() && !nameMissing, modifier = Modifier.fillMaxWidth()) {
                Text("Save")
            }
            Text(
                "Lowering a limit or adding apps applies at once. Raising it, removing apps or deleting it takes 24 hours.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!isNew) {
                OutlinedButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) { Text("Delete limit") }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this limit?") },
            text = { Text("It stays in force for another 24 hours.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}
