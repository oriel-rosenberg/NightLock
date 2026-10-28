package com.orirose.nightlock.block

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.orirose.nightlock.configRepository
import com.orirose.nightlock.data.loadApp
import com.orirose.nightlock.ui.NightLockTheme
import com.orirose.nightlock.ui.blockDescription
import com.orirose.nightlock.ui.enableNightEdgeToEdge
import com.orirose.nightlock.ui.rememberNow

/** The full-screen cover shown over a blocked app. Leaving it, by any route, lands on the home screen. */
class BlockActivity : ComponentActivity() {
    private var blockedPackage by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableNightEdgeToEdge()
        blockedPackage = intent.getStringExtra(EXTRA_PACKAGE)
        // Back would return to the blocked app, which is right underneath.
        onBackPressedDispatcher.addCallback(this) { goHome() }
        setContent {
            val config by configRepository.config.collectAsStateWithLifecycle(initialValue = null)
            val now = rememberNow()
            val current = config
            val app = blockedPackage
            // When this app's block ends while the screen is up, step aside: the app underneath is allowed again.
            LaunchedEffect(current, now, app) {
                if (current != null && app != null && (!current.isBlocking(now) || app !in current.blockedPackagesAt(now))) finish()
            }
            val context = LocalContext.current
            NightLockTheme {
                BlockScreen(app, reason = current?.let { blockDescription(it, now, context, app) }, onGoHome = ::goHome)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        blockedPackage = intent.getStringExtra(EXTRA_PACKAGE)
    }

    private fun goHome() {
        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    companion object {
        const val EXTRA_PACKAGE = "com.orirose.nightlock.extra.PACKAGE"
    }
}

@Composable
private fun BlockScreen(packageName: String?, reason: String?, onGoHome: () -> Unit) {
    val context = LocalContext.current
    val app = remember(packageName) { packageName?.let { loadApp(context, it) } }
    Surface(Modifier.fillMaxSize()) {
        // Centred when it fits; scrolls in landscape or with a large font, so the buttons stay reachable.
        Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()).padding(32.dp),
            ) {
                app?.let { Image(it.icon, contentDescription = null, modifier = Modifier.size(72.dp)) }
                Text(
                    "${app?.label ?: "This app"} is blocked",
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center,
                )
                reason?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
                Spacer(Modifier.height(24.dp))
                Button(onClick = onGoHome, modifier = Modifier.fillMaxWidth()) { Text("Go to home screen") }
                // Deliberately no shortcut: unlocking means going to NightLock yourself.
                Text(
                    "Really need it? Unlocking is done in the NightLock app.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
