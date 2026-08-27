package com.anas.applocker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Fired by [AlarmScheduler] roughly every 2.5 minutes. Checks whether Accessibility
 * (and therefore app-locking) is still active, alerts the user immediately if not, and
 * always re-arms the next heartbeat before returning.
 */
class HeartbeatReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        ProtectionNotifier.checkAndNotifyIfNeeded(context)
        // Always re-schedule, whether or not the check above found a problem, so the
        // heartbeat keeps ticking every 2.5 minutes indefinitely.
        AlarmScheduler.scheduleHeartbeat(context)
    }
}
