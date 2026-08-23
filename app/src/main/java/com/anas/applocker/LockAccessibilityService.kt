package com.anas.applocker

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.EditText
import android.widget.TextView

/**
 * Watches which app is in the foreground. When a locked app appears,
 * it instantly draws a full-screen PIN overlay directly via
 * WindowManager (not by launching an Activity) so there is no delay
 * and the target app never flashes on screen before being covered.
 */
class LockAccessibilityService : AccessibilityService() {

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var overlayShowingFor: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        if (pkg == packageName) return

        val store = LockedAppsStore(this)

        if (store.isLocked(pkg)) {
            if (!unlockedPackages.contains(pkg) && overlayShowingFor != pkg) {
                showLockOverlay(pkg)
            }
        } else {
            // Foreground moved to something ordinary (launcher, non-locked app) —
            // reset so locked apps ask for the PIN again next time they're opened.
            unlockedPackages.clear()
            removeOverlay()
        }
    }

    private fun showLockOverlay(targetPackage: String) {
        val canDrawOverlay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else true

        if (!canDrawOverlay) {
            // Permission not granted yet — fall back to the slower Activity
            // approach so locking still works, just with the old flash.
            val intent = Intent(this, LockOverlayActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(LockOverlayActivity.EXTRA_TARGET_PACKAGE, targetPackage)
            }
            startActivity(intent)
            return
        }

        removeOverlay()
        overlayShowingFor = targetPackage

        val inflater = LayoutInflater.from(this)
        val view = inflater.inflate(R.layout.activity_lock_overlay, null)

        val pinManager = PinManager(this)
        val label = view.findViewById<TextView>(R.id.lockedAppLabel)
        val input = view.findViewById<EditText>(R.id.overlayPinInput)
        val error = view.findViewById<TextView>(R.id.overlayErrorText)
        val button = view.findViewById<Button>(R.id.overlayUnlockButton)

        val appLabel = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(targetPackage, 0))
        } catch (e: Exception) { null }
        label.text = "\"${appLabel ?: targetPackage}\" is locked"

        button.setOnClickListener {
            val entered = input.text.toString()
            if (pinManager.check(entered) == PinManager.PinResult.REAL) {
                unlockedPackages.add(targetPackage)
                removeOverlay()
            } else {
                error.text = "Incorrect PIN"
                input.text.clear()
            }
        }

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )

        try {
            windowManager?.addView(view, params)
            overlayView = view
        } catch (e: Exception) {
            overlayShowingFor = null
        }
    }

    private fun removeOverlay() {
        overlayView?.let {
            try {
                windowManager?.removeView(it)
            } catch (e: Exception) {
                // View was already removed — safe to ignore.
            }
        }
        overlayView = null
        overlayShowingFor = null
    }

    override fun onInterrupt() {
        removeOverlay()
    }

    override fun onDestroy() {
        super.onDestroy()
        removeOverlay()
    }

    companion object {
        /** Packages the user has unlocked during the current "session". */
        val unlockedPackages = mutableSetOf<String>()
    }
}
