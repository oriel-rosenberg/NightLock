package com.orirose.nightlock

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.orirose.nightlock.block.BlockerService
import com.orirose.nightlock.data.Schedule
import com.orirose.nightlock.ui.AppListScreen
import com.orirose.nightlock.ui.ChecklistScreen
import com.orirose.nightlock.ui.HomeScreen
import com.orirose.nightlock.ui.MainViewModel
import com.orirose.nightlock.ui.NightLockTheme
import com.orirose.nightlock.ui.ScheduleScreen
import com.orirose.nightlock.ui.enableNightEdgeToEdge
import com.orirose.nightlock.ui.rememberNow
import com.orirose.nightlock.ui.rememberSetupState
import kotlinx.serialization.json.Json

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableNightEdgeToEdge()
        setContent {
            NightLockTheme { NightLockRoot() }
        }
    }
}

private enum class Screen { Home, BlockList, Allowlist, Checklist, Schedule, ScheduleApps }

/** Keeps an unsaved schedule across rotation and process death, as JSON. */
private val draftSaver = Saver<MutableState<Schedule?>, String>(
    save = { state -> state.value?.let { Json.encodeToString(Schedule.serializer(), it) } ?: "" },
    restore = { mutableStateOf(if (it.isEmpty()) null else Json.decodeFromString(Schedule.serializer(), it)) },
)

@Composable
private fun NightLockRoot(vm: MainViewModel = viewModel()) {
    var screen by rememberSaveable { mutableStateOf(Screen.Home) }
    var draft by rememberSaveable(saver = draftSaver) { mutableStateOf<Schedule?>(null) }
    val config by vm.config.collectAsStateWithLifecycle()
    val essentials by vm.essentials.collectAsStateWithLifecycle()
    val apps by vm.apps.collectAsStateWithLifecycle()
    val serviceRunning by BlockerService.running.collectAsStateWithLifecycle()
    val setup = rememberSetupState(serviceRunning)
    val now = rememberNow()

    val cfg = config
    val ess = essentials
    if (cfg == null || ess == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    val allowlist = cfg.allowlist(ess)
    val blocked = cfg.blockedPackages - allowlist
    val editing = draft

    // The allowlist closes as soon as a block starts, even if it was open; the editor needs a draft.
    val current = when {
        screen == Screen.Allowlist && cfg.isBlocking(now) -> Screen.Home
        (screen == Screen.Schedule || screen == Screen.ScheduleApps) && editing == null -> Screen.Home
        else -> screen
    }
    // Make a forced return to Home stick, so the allowlist doesn't reopen by itself once the block ends.
    LaunchedEffect(current) { screen = current }
    BackHandler(enabled = current != Screen.Home) {
        screen = if (current == Screen.ScheduleApps) Screen.Schedule else Screen.Home
    }
    // Re-read the installed apps each time a picker opens, so a newly installed app shows up.
    LaunchedEffect(current) { if (current == Screen.BlockList || current == Screen.Allowlist || current == Screen.ScheduleApps) vm.refreshApps() }

    fun openEditor(schedule: Schedule) {
        draft = schedule
        screen = Screen.Schedule
    }

    when (current) {
        Screen.Home -> HomeScreen(
            config = cfg,
            now = now,
            blockedCount = blocked.size,
            allowedCount = apps?.count { it.packageName in allowlist },
            setup = setup,
            onManualBlock = vm::setManualBlock,
            onScheduleEnabled = vm::setScheduleEnabled,
            onEditSchedule = ::openEditor,
            onAddSchedule = { openEditor(Schedule()) },
            onOpenBlockList = { screen = Screen.BlockList },
            onOpenAllowlist = { screen = Screen.Allowlist },
            onOpenChecklist = { screen = Screen.Checklist },
        )
        Screen.BlockList -> AppListScreen(
            title = "Blocked apps",
            subtitle = "The main block list: used by Block now, and by schedules set to it.",
            apps = apps,
            selected = blocked,
            disabledReason = { if (it in allowlist) "Always allowed" else null },
            onToggle = vm::setBlocked,
            onBack = { screen = Screen.Home },
        )
        Screen.Allowlist -> AppListScreen(
            title = "Always allowed",
            subtitle = "Ticked apps are never blocked. Essential apps can't be removed.",
            apps = apps,
            selected = allowlist,
            disabledReason = { if (it in ess.locked) "Essential: always allowed" else null },
            onToggle = { pkg, allowed -> vm.setAllowed(pkg, allowed, ess) },
            onBack = { screen = Screen.Home },
        )
        Screen.Checklist -> ChecklistScreen(setup = setup, onBack = { screen = Screen.Home })
        Screen.Schedule -> ScheduleScreen(
            draft = editing!!,
            isNew = cfg.schedules.none { it.id == editing.id },
            mainListCount = blocked.size,
            ownListCount = ((editing.packages ?: emptySet()) - allowlist).size,
            notificationsAllowed = setup.notifications,
            onChange = { draft = it },
            onChooseApps = { screen = Screen.ScheduleApps },
            onSave = {
                vm.saveSchedule(editing)
                draft = null
                screen = Screen.Home
            },
            onDelete = {
                vm.deleteSchedule(editing.id)
                draft = null
                screen = Screen.Home
            },
            onBack = {
                draft = null
                screen = Screen.Home
            },
        )
        Screen.ScheduleApps -> AppListScreen(
            title = "Apps for ${editing!!.name}",
            subtitle = "Ticked apps are blocked while this schedule runs. Saved with the schedule.",
            apps = apps,
            selected = (editing.packages ?: emptySet()) - allowlist,
            disabledReason = { if (it in allowlist) "Always allowed" else null },
            onToggle = { pkg, on ->
                val packages = editing.packages ?: emptySet()
                draft = editing.copy(packages = if (on) packages + pkg else packages - pkg)
            },
            onBack = { screen = Screen.Schedule },
        )
    }
}
