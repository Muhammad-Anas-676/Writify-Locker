package com.anas.applocker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat

/**
 * Fires a (visible, unlike the silent foreground one) notification when the heartbeat,
 * the periodic worker, or the boot receiver notices that Accessibility got switched off.
 * This can't be done automatically - Android only lets the *user* flip that toggle back
 * on, there is no API for an app to re-enable it for itself - so the fastest fix is
 * telling the user right away instead of them noticing an hour later that an app opened
 * unlocked.
 */
object ProtectionNotifier {

    private const val CHANNEL_ID = "writify_protection_alert"
    private const val NOTIFICATION_ID = 4178

    fun checkAndNotifyIfNeeded(context: Context) {
        if (isAccessibilityEnabled(context)) return
        notify(context)
    }

    private fun isAccessibilityEnabled(context: Context): Boolean {
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledServices.contains(context.packageName)
    }

    private fun notify(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val existing = manager.getNotificationChannel(CHANNEL_ID)
            if (existing == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Writify protection alerts",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Lets you know if app-locking protection needs to be turned back on"
                }
                manager.createNotificationChannel(channel)
            }
        }

        val openIntent = Intent(context, DashboardActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getActivity(context, 0, openIntent, flags)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("Writify protection is off")
            .setContentText("App locking stopped working - tap to turn it back on.")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        try {
            manager.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) { }
    }
}
