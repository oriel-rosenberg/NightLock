package com.orirose.nightlock.ui

import android.os.SystemClock
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
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.orirose.nightlock.Durations
import java.security.SecureRandom
import kotlinx.coroutines.delay

private const val TEXT_LENGTH = 25
private const val LETTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
private val random = SecureRandom()

/** A fresh text of random upper- and lower-case letters. */
internal fun randomLetters(length: Int = TEXT_LENGTH): String = buildString { repeat(length) { append(LETTERS[random.nextInt(LETTERS.length)]) } }

/**
 * The way out of a block: wait with this screen open, type a random text, then pick a short pause or the
 * rest of the block. Leaving the screen (Home, another app, screen off) cancels it, so the wait starts over.
 *
 * @param restLabel the second choice, e.g. "Rest of tonight (until 07:00)".
 */
@Composable
fun UnlockScreen(waitMinutes: Int, restLabel: String, onPauseShort: () -> Unit, onPauseRest: () -> Unit, onCancel: () -> Unit) {
    // Keep the screen on during the wait; letting it sleep would count as leaving.
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { onCancel() }

    val waitMs = Durations.minutes(waitMinutes)
    val startedAt = remember { SystemClock.elapsedRealtime() }
    var leftMs by remember { mutableLongStateOf(waitMs) }
    LaunchedEffect(startedAt) {
        while (leftMs > 0) {
            leftMs = (waitMs - (SystemClock.elapsedRealtime() - startedAt)).coerceAtLeast(0)
            delay(250)
        }
    }

    var target by remember { mutableStateOf(randomLetters()) }
    var typed by remember { mutableStateOf("") }
    var mistakes by remember { mutableStateOf(0) }

    Surface(Modifier.fillMaxSize()) {
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onCancel) { Text("← Cancel") }
                Text("Unlock", style = MaterialTheme.typography.titleLarge)
            }
            when {
                leftMs > 0 -> {
                    val seconds = (leftMs + 999) / 1000
                    Text("Wait", style = MaterialTheme.typography.titleMedium)
                    Text("%d:%02d".format(seconds / 60, seconds % 60), style = MaterialTheme.typography.displayMedium)
                    Text("Stay on this screen. Leaving it, or letting the screen turn off, starts the wait over.")
                }
                typed != target -> {
                    Text("Type this exactly", style = MaterialTheme.typography.titleMedium)
                    Text(target, fontFamily = FontFamily.Monospace, fontSize = 22.sp, letterSpacing = 1.sp)
                    OutlinedTextField(
                        value = typed,
                        onValueChange = { new ->
                            when {
                                // More than one letter at once is a paste or a suggestion: ignore it.
                                new.length > typed.length + 1 -> Unit
                                target.startsWith(new) -> typed = new
                                else -> {
                                    target = randomLetters()
                                    typed = ""
                                    mistakes++
                                }
                            }
                        },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.None,
                            autoCorrectEnabled = false,
                            // Tells the keyboard not to suggest or remember words.
                            keyboardType = KeyboardType.Password,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        if (mistakes > 0) "Typo: here's a new text. Upper and lower case count."
                        else "${typed.length} of ${target.length}. Upper and lower case count; a typo gives you a new text.",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (mistakes > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> {
                    Text("Unlocked. What do you need?", style = MaterialTheme.typography.titleMedium)
                    Button(onClick = onPauseShort, modifier = Modifier.fillMaxWidth()) {
                        Text("Pause ${Durations.PAUSE_MINUTES} ${Durations.minuteUnit}")
                    }
                    OutlinedButton(onClick = onPauseRest, modifier = Modifier.fillMaxWidth()) { Text(restLabel) }
                    Text(
                        "Settings stay locked either way. When the pause ends, blocking comes back by itself.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
