package com.orirose.nightlock.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.orirose.nightlock.data.AppEntry

/**
 * A searchable list of apps with a checkbox each. Used for both the block list and the allowlist.
 *
 * @param disabledReason why an app can't be toggled here (shown in place of its package name), or null.
 * @param note something to show about an app that can still be toggled, e.g. a change waiting 24 hours.
 */
@Composable
fun AppListScreen(
    title: String,
    subtitle: String,
    apps: List<AppEntry>?,
    selected: Set<String>,
    disabledReason: (String) -> String?,
    onToggle: (packageName: String, checked: Boolean) -> Unit,
    onBack: () -> Unit,
    note: (String) -> String? = { null },
) {
    var query by rememberSaveable { mutableStateOf("") }
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.safeDrawingPadding()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)) {
                TextButton(onClick = onBack) { Text("← Back") }
                Text(title, style = MaterialTheme.typography.titleLarge)
            }
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search apps") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            )
            if (apps == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else {
                val shown = remember(apps, query) {
                    val q = query.trim()
                    apps.filter { q.isEmpty() || it.label.contains(q, ignoreCase = true) || it.packageName.contains(q, ignoreCase = true) }
                }
                LazyColumn(Modifier.fillMaxSize()) {
                    items(shown, key = { it.packageName }) { app ->
                        AppRow(app, checked = app.packageName in selected, disabledReason(app.packageName), note(app.packageName)) { checked ->
                            onToggle(app.packageName, checked)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppRow(app: AppEntry, checked: Boolean, disabledReason: String?, note: String?, onToggle: (Boolean) -> Unit) {
    val enabled = disabledReason == null
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = onToggle)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Image(app.icon, contentDescription = null, modifier = Modifier.size(40.dp))
        Column(Modifier.weight(1f)) {
            Text(app.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                disabledReason ?: note ?: app.packageName,
                style = MaterialTheme.typography.bodySmall,
                color = if (enabled && note == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}
