package com.anas.applocker

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock

/**
 * Schedules [HeartbeatReceiver] to fire roughly every 15 minutes.
 *
 * This used to fire every 2.5 minutes using setExactAndAllowWhileIdle(), which woke the
 * CPU far too often - on Infinix Hot 8 Lite (XOS) and Android 12-14 in general, that
 * pattern is exactly what triggers the Phantom Process Killer and OEM battery watchdogs
 * to force-stop the app, which is what was silently switching Accessibility off after
 * 1-2 hours. A 15-minute interval is gentle enough to avoid that, and stays in sync with
 * the floor Android enforces on WorkManager anyway (see [KeepAliveWorker], the redundant
 * safety net at the same cadence). Fast re-arming after real events (unlock, screen on)
 * is instead handled by [LockAccessibilityService]'s ACTION_SCREEN_ON / ACTION_USER_PRESENT
 * receivers, which call [scheduleHeartbeat] directly - so responsiveness doesn't depend on
 * a short alarm interval, only on genuine user-facing events.
 */
object AlarmScheduler {

    private const val INTERVAL_MS = 900_000L // 15 minutes
    private const val REQUEST_CODE = 5501

    fun scheduleHeartbeat(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(context, HeartbeatReceiver::class.java)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags)

        val triggerAt = SystemClock.elapsedRealtime() + INTERVAL_MS

        // Deliberately inexact + Doze-aware for all API levels now: at a 15-minute cadence
        // there's no real need for setExactAndAllowWhileIdle's extra wake-precision, and
        // avoiding exact alarms altogether means no dependency on the user granting the
        // "Alarms & reminders" special permission on Android 12+.
        try {
            alarmManager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pendingIntent)
        } catch (e: Exception) { }
    }
}
