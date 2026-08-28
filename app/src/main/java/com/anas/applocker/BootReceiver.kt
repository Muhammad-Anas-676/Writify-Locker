package com.anas.applocker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Re-arms the AlarmManager heartbeat and the WorkManager safety-net job after a reboot
 * or an app update, since both get wiped by the OS at that point.
 *
 * One thing this deliberately does NOT try to do: automatically flip Accessibility back
 * on. Android disables every accessibility service on every boot for security, and there
 * is no public API for an app to re-enable itself - only the user, from Settings, can do
 * that. So instead this posts a heads-up notification prompting them to do exactly that,
 * as fast as possible after boot rather than leaving it to be noticed by accident later.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON" &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return

        AlarmScheduler.scheduleHeartbeat(context)
        KeepAliveWorker.schedulePeriodic(context)
        ProtectionNotifier.checkAndNotifyIfNeeded(context)
    }
}
