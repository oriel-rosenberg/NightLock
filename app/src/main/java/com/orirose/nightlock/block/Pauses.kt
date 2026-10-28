package com.orirose.nightlock.block

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.orirose.nightlock.MainActivity
import com.orirose.nightlock.R
import com.orirose.nightlock.formatTime
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The notification shown during a pause, counting down to when blocking comes back. Android counts it
 * down and removes it by itself, so nothing in NightLock runs meanwhile.
 */
object Pauses {
    private const val CHANNEL = "pause"
    private const val ID = 1

    fun show(context: Context, until: LocalDateTime) {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Pause", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Time left in a pause"
            },
        )
        val endMs = until.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val left = endMs - System.currentTimeMillis()
        if (left <= 0) return cancel(context)
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Paused: blocking again at ${context.formatTime(until.toLocalTime())}")
            .setContentText("Open NightLock to end the pause early.")
            .setContentIntent(open)
            .setOngoing(true)
            .setShowWhen(true)
            .setWhen(endMs)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setTimeoutAfter(left)
            .build()
        manager.notify(ID, notification)
    }

    fun cancel(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(ID)
    }
}
