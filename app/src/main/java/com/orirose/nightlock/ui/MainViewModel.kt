package com.orirose.nightlock.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.orirose.nightlock.configRepository
import com.orirose.nightlock.data.AppEntry
import com.orirose.nightlock.data.Config
import com.orirose.nightlock.data.Essentials
import com.orirose.nightlock.data.Schedule
import com.orirose.nightlock.data.loadLaunchableApps
import java.time.LocalDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val repository = app.configRepository

    val config: StateFlow<Config?> = repository.config.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val essentials: StateFlow<Essentials?> = flow {
        emit(withContext(Dispatchers.Default) { Essentials.detect(app) })
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _apps = MutableStateFlow<List<AppEntry>?>(null)
    val apps: StateFlow<List<AppEntry>?> = _apps.asStateFlow()

    init {
        refreshApps()
    }

    /** Reloads the installed apps. The old list stays on screen until the new one is ready. */
    fun refreshApps() {
        viewModelScope.launch { _apps.value = withContext(Dispatchers.IO) { loadLaunchableApps(getApplication()) } }
    }

    fun setManualBlock(on: Boolean) = edit { it.copy(manualBlock = on) }

    fun setBlocked(packageName: String, blocked: Boolean) = edit {
        it.copy(blockedPackages = if (blocked) it.blockedPackages + packageName else it.blockedPackages - packageName)
    }

    fun setAllowed(packageName: String, allowed: Boolean, essentials: Essentials) = edit { config ->
        // Checked here as well as in the UI: the allowlist can't change while a block is on,
        // and the locked essentials can never leave it.
        if (config.isBlocking(LocalDateTime.now()) || packageName in essentials.locked) return@edit config
        val current = config.allowedPackages ?: essentials.defaultAllowlist
        config.copy(allowedPackages = if (allowed) current + packageName else current - packageName)
    }

    /** Adds the schedule, or replaces the saved one with the same id. */
    fun saveSchedule(schedule: Schedule) = edit { config ->
        val exists = config.schedules.any { it.id == schedule.id }
        config.copy(schedules = if (exists) config.schedules.map { if (it.id == schedule.id) schedule else it } else config.schedules + schedule)
    }

    fun deleteSchedule(id: String) = edit { config -> config.copy(schedules = config.schedules.filterNot { it.id == id }) }

    fun setScheduleEnabled(id: String, enabled: Boolean) = edit { config ->
        config.copy(schedules = config.schedules.map { if (it.id == id) it.copy(enabled = enabled) else it })
    }

    private fun edit(transform: (Config) -> Config) {
        viewModelScope.launch { repository.update(transform) }
    }
}
