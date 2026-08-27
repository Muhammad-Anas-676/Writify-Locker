package com.anas.applocker

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock

/**
 * Schedules [HeartbeatReceiver] to fire roughly every 2.5 minutes.
 *
 * Important, so this doesn't look like a bug later: Android's WorkManager cannot run
 * anything more often than every 15 minutes - that floor is enforced by the OS itself,
 * not by WorkManager's code, so it's used separately as a slower 15-min safety net
 * (see [KeepAliveWorker]). For the tighter 2.5-minute interval, AlarmManager is the only
 * mechanism that can do it, and each firing re-schedules the next one itself rather than
 * using setRepeating - self-rescheduled exact alarms survive Doze restarts more reliably.
 */
object AlarmScheduler {

    private const val INTERVAL_MS = 150_000L // 2.5 minutes
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

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
                // User hasn't granted the "Alarms & reminders" special permission on Android 12+ -
                // fall back to an inexact-but-Doze-aware alarm instead of crashing.
                alarmManager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pendingIntent)
            } else {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pendingIntent)
            }
        } catch (e: SecurityException) {
            try {
                alarmManager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pendingIntent)
            } catch (e2: Exception) { }
        } catch (e: Exception) { }
    }

    /** True if the device is on API 31+ and the user hasn't granted exact-alarm permission. */
    fun needsExactAlarmPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return false
        return !alarmManager.canScheduleExactAlarms()
    }
}
