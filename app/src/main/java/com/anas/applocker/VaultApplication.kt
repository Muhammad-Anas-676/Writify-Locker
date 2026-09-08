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
    }

    override fun onStop(owner: LifecycleOwner) {
        backgroundedAt = System.currentTimeMillis()
    }

    override fun onStart(owner: LifecycleOwner) {
        val bgTime = backgroundedAt
        if (bgTime != null) {
            val elapsedSeconds = (System.currentTimeMillis() - bgTime) / 1000
            if (elapsedSeconds >= AUTO_RELOCK_SECONDS) {
                needsReauth = true
            }
        }
        backgroundedAt = null
    }

    companion object {
        const val AUTO_RELOCK_SECONDS = 15L

        private var backgroundedAt: Long? = null

        /** Set true when the app returns from background past the timeout. */
        @Volatile
        var needsReauth: Boolean = false
    }
}
