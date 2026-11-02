package com.orirose.nightlock.data

import android.content.Context
import android.net.Uri
import android.util.Log
import com.orirose.nightlock.LOG_TAG
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/*
 * A copy of the settings in a file the user chose, outside the app, so they outlive uninstalling it. Android
 * clears an app's own storage on uninstall, and a reinstalled app can't read files its old self made: the file
 * has to be picked by hand on both sides, which needs no permission.
 */

private const val MAX_BACKUP_BYTES = 1_000_000
private val backupJson = Json { ignoreUnknownKeys = true; prettyPrint = true }

/** The whole file. The named field is what tells a NightLock backup from any other JSON file. */
@Serializable
private data class BackupFile(val nightlockSettings: Config)

/** The file's content: the settings, without what only means something on this install, right now. */
fun encodeBackup(config: Config): String = backupJson.encodeToString(
    BackupFile.serializer(),
    BackupFile(config.copy(pausedUntil = null, limitPauses = emptyMap(), backupUri = null)),
)

/** The settings in a backup file's content, or null if it isn't one. */
fun decodeBackup(text: String): Config? =
    runCatching { backupJson.decodeFromString(BackupFile.serializer(), text).nightlockSettings }.getOrNull()

/** Overwrites the backup file. False if that failed, say because the file was deleted. */
suspend fun writeBackup(context: Context, uri: Uri, config: Config): Boolean = withContext(Dispatchers.IO) {
    runCatching {
        checkNotNull(context.contentResolver.openOutputStream(uri, "wt")).use { it.write(encodeBackup(config).toByteArray()) }
    }.onFailure { Log.e(LOG_TAG, "Couldn't write the settings backup", it) }.isSuccess
}

/** The settings in the file at [uri], or null if it can't be read, is too big, or isn't a backup. */
suspend fun readBackup(context: Context, uri: Uri): Config? = withContext(Dispatchers.IO) {
    runCatching {
        checkNotNull(context.contentResolver.openInputStream(uri)).use { input ->
            val buffer = ByteArray(MAX_BACKUP_BYTES + 1)
            var size = 0
            while (size < buffer.size) {
                val read = input.read(buffer, size, buffer.size - size)
                if (read < 0) break
                size += read
            }
            check(size <= MAX_BACKUP_BYTES)
            decodeBackup(String(buffer, 0, size))
        }
    }.onFailure { Log.e(LOG_TAG, "Couldn't read the settings backup", it) }.getOrNull()
}
