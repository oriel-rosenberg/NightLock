package com.orirose.nightlock.data

import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupTest {
    private val noon = LocalDateTime.of(2026, 10, 26, 12, 0)
    private val settings = Config(
        blockedPackages = setOf("x.app"),
        allowedPackages = setOf("chat.app"),
        manualBlock = true,
        schedules = listOf(Schedule(id = "bed", name = "Bedtime")),
        unlockWaitMinutes = 10,
        waitPauseMinutes = 5,
        limits = listOf(TimeLimit(id = "x", packages = setOf("x.app"), minutes = 30)),
        pending = listOf(Pending(applyAt = noon, unblock = "x.app")),
    )

    @Test
    fun everySettingComesBack() {
        assertEquals(settings, decodeBackup(encodeBackup(settings)))
    }

    @Test
    fun whatOnlyMeansSomethingRightNowIsLeftOut() {
        val running = settings.copy(pausedUntil = noon, limitPauses = mapOf("x" to noon), backupUri = "content://file")
        assertEquals(settings, decodeBackup(encodeBackup(running)))
    }

    @Test
    fun otherFilesAreRefused() {
        assertNull(decodeBackup("not a backup"))
        assertNull(decodeBackup("{}"))
        assertNull(decodeBackup("""{"name":"some other app"}"""))
    }

    @Test
    fun aFreshInstallIsUntouchedEvenWithABackupFileChosen() {
        assertTrue(Config().isUntouched)
        assertTrue(Config(backupUri = "content://file").isUntouched)
        assertFalse(settings.isUntouched)
        assertFalse(Config(blockedPackages = setOf("x.app")).isUntouched)
    }
}
