package com.anas.applocker

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent

/**
 * Watches which app is in the foreground. When a locked app appears,
 * it launches the full-screen PIN overlay on top of it. Once unlocked,
 * that package stays unlocked until the user navigates away to some
 * other (non-locked) app — then it re-locks for next time.
 */
class LockAccessibilityService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val packageName = event?.packageName?.toString() ?: return
        if (packageName == getPackageName()) return

        val store = LockedAppsStore(this)

        if (store.isLocked(packageName)) {
            if (!unlockedPackages.contains(packageName)) {
                val intent = Intent(this, LockOverlayActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra(LockOverlayActivity.EXTRA_TARGET_PACKAGE, packageName)
                }
                startActivity(intent)
            }
        } else {
            // Left every locked app back to something ordinary (e.g. launcher) —
            // reset so locked apps ask for the PIN again next time they're opened.
            unlockedPackages.clear()
        }
    }

    override fun onInterrupt() {}

    companion object {
        /** Packages the user has unlocked during the current "session". */
        val unlockedPackages = mutableSetOf<String>()
    }
}
