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
import com.orirose.nightlock.data.Occurrence
import com.orirose.nightlock.formatTime
import java.time.Duration
import java.time.LocalDateTime

/** The wind-down warning: a notification some minutes before a schedule starts. */
object Warnings {
    private const val CHANNEL = "wind_down"

    fun show(context: Context, occurrence: Occurrence, appCount: Int) {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Wind-down warning", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "A heads-up before a bedtime block starts"
            },
        )
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val untilStart = Duration.between(LocalDateTime.now(), occurrence.start).toMillis().coerceAtLeast(0)
        val apps = if (appCount == 1) "1 app" else "$appCount apps"
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("${occurrence.schedule.name} starts at ${context.formatTime(occurrence.start.toLocalTime())}")
            .setContentText("$apps will be blocked. Time to wind down.")
            .setContentIntent(open)
            .setAutoCancel(true)
            // Posted again while still showing (the service restarted mid-warning): update it silently.
            .setOnlyAlertOnce(true)
            // Gone once the block starts; it has said what it needed to.
            .setTimeoutAfter(untilStart.coerceAtLeast(1))
            .build()
        manager.notify(idOf(occurrence.schedule.id), notification)
    }

    /** Removes a schedule's warning, e.g. once it was switched off or moved. */
    fun cancel(context: Context, scheduleId: String) {
        context.getSystemService(NotificationManager::class.java)?.cancel(idOf(scheduleId))
    }

    private fun idOf(scheduleId: String) = scheduleId.hashCode()
}
