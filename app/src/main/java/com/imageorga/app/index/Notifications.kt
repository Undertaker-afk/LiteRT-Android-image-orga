package com.imageorga.app.index

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.ForegroundInfo
import com.imageorga.app.R
import com.imageorga.app.ui.MainActivity

object Notifications {
    private const val CHANNEL = "progress"
    const val ID_INDEX = 1001
    const val ID_DOWNLOAD = 1002
    const val ID_RESULT = 1003

    fun createChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL, context.getString(R.string.channel_progress), NotificationManager.IMPORTANCE_LOW
        ).apply { description = context.getString(R.string.channel_progress_desc) }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Ongoing notification with a progress bar; [total] of 0 shows an indeterminate bar. */
    fun progress(context: Context, title: String, text: String, done: Int, total: Int, cancel: PendingIntent?): Notification {
        val b = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_index)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(total, done, total <= 0)
            .setContentIntent(openApp(context))
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (cancel != null) b.addAction(0, "Stop", cancel)
        return b.build()
    }

    fun result(context: Context, title: String, text: String): Notification =
        NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_index)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
            .build()

    fun foreground(id: Int, notification: Notification): ForegroundInfo =
        if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(id, notification)
        }

    fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun post(context: Context, id: Int, notification: Notification) {
        if (!canPost(context)) return
        try {
            context.getSystemService(NotificationManager::class.java).notify(id, notification)
        } catch (_: SecurityException) {
        }
    }

    fun cancel(context: Context, id: Int) {
        context.getSystemService(NotificationManager::class.java).cancel(id)
    }
}
