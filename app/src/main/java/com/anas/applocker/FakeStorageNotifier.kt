package com.anas.applocker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * Posts a realistic-looking "Storage Space Running Out" system notification. Purely a
 * decoy/stealth-mode UI element - tapping it opens [FakeStorageDetailActivity], which shows
 * a fake storage breakdown and a "cleanup" that always fails. Controlled entirely by
 * SettingsStore.isFakeStorageNotificationEnabled(); off by default so it never surprises
 * anyone who didn't turn it on.
 */
object FakeStorageNotifier {

    private const val CHANNEL_ID = "system_storage_alert"
    private const val NOTIFICATION_ID = 9231

    fun showIfEnabled(context: Context) {
        val settingsStore = SettingsStore(context)
        if (!settingsStore.isFakeStorageNotificationEnabled()) return
        show(context)
    }

    fun show(context: Context) {
        ensureChannel(context)

        val detailIntent = Intent(context, FakeStorageDetailActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getActivity(context, NOTIFICATION_ID, detailIntent, flags)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("Storage Space Running Out")
            .setContentText("Some system functions may not work. Tap to free up internal storage.")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("Some system functions may not work. Tap to free up internal storage.")
            )
            .setCategory(NotificationCompat.CATEGORY_SYSTEM)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        try {
            if (androidx.core.content.ContextCompat.checkSelfPermission(
                    context, android.Manifest.permission.POST_NOTIFICATIONS
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            ) {
                return
            }
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // Notification permission not granted - fail silently, this is a non-critical extra.
        }
    }

    fun cancel(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "System storage alerts",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Storage warning notifications"
        }
        manager.createNotificationChannel(channel)
    }
}
