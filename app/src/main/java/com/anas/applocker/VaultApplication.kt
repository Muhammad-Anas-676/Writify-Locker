package com.anas.applocker

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner

/**
 * Tracks whole-app background/foreground transitions (as opposed to a
 * single Activity's onPause/onResume, which also fires during normal
 * in-app navigation). Used to power the auto-relock timer: if the
 * entire app was backgrounded for longer than AUTO_RELOCK_SECONDS,
 * the next screen shown re-demands the PIN.
 */
class VaultApplication : Application(), DefaultLifecycleObserver {

    override fun onCreate() {
        super<Application>.onCreate()
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)

        // Re-assert the keep-alive layers every time the app process starts, not just when
        // the Accessibility Service connects - covers the case where the service died and
        // only the app itself got woken back up (e.g. by the boot receiver or the worker).
        AlarmScheduler.scheduleHeartbeat(this)
        KeepAliveWorker.schedulePeriodic(this)
        IconAliasManager.applyStoredAlias(this)
    }

    override fun onStop(owner: LifecycleOwner) {
        backgroundedAt = System.currentTimeMillis()
    }

    override fun onStart(owner: LifecycleOwner) {
        val bgTime = backgroundedAt
        if (bgTime != null) {
            val elapsedSeconds = (System.currentTimeMillis() - bgTime) / 1000
            val configuredSeconds = SettingsStore(this).getAutoRelockSeconds()
            if (elapsedSeconds >= configuredSeconds) {
                needsReauth = true
            }
        }
        backgroundedAt = null
    }

    companion object {
        /** Default, used only until the user picks something else in Settings. */
        const val AUTO_RELOCK_SECONDS = 15L

        private var backgroundedAt: Long? = null

        /** Set true when the app returns from background past the timeout. */
        @Volatile
        var needsReauth: Boolean = false
    }
}
