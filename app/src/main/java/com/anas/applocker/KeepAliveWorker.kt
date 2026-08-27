package com.anas.applocker

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Second, independent redundancy layer for the keep-alive system, on top of the
 * foreground service and the AlarmManager heartbeat. 15 minutes is the shortest interval
 * Android's WorkManager allows for periodic work - that limit is enforced by the
 * platform, not a choice made here - so this exists purely as a backup in case the
 * AlarmManager-based heartbeat ever gets cleared by an aggressive OEM battery manager;
 * WorkManager persists its own schedule across reboots automatically once (re)enqueued.
 */
class KeepAliveWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        ProtectionNotifier.checkAndNotifyIfNeeded(applicationContext)
        // Also make sure the tighter 2.5-min alarm chain hasn't been silently cleared.
        AlarmScheduler.scheduleHeartbeat(applicationContext)
        return Result.success()
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "writify_keep_alive"

        fun schedulePeriodic(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                .build()

            val request = PeriodicWorkRequestBuilder<KeepAliveWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
