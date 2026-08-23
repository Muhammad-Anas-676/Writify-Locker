package com.anas.applocker

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView

/**
 * Shows a decoy "Storage is Full" screen with a hidden secret PIN listener.
 * If 15 seconds pass without unlocking, it exits to the Home screen instantly.
 */
class LockAccessibilityService : AccessibilityService() {

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var overlayShowingFor: String? = null

    private val timeoutHandler = Handler(Looper.getMainLooper())
    private var remainingSeconds = 15
    private var countdownRunnable: Runnable? = null

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
            // User moved away from the locked app (e.g. went to launcher or allowed app)
            unlockedPackages.clear()
            removeOverlay()
        }
    }

    private fun showLockOverlay(targetPackage: String) {
        val canDrawOverlay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else true

        if (!canDrawOverlay) {
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
        val hiddenInput = view.findViewById<EditText>(R.id.hiddenPinInput)
        val timerText = view.findViewById<TextView>(R.id.timerDecoyText)
        val btnClose = view.findViewById<Button>(R.id.btnDecoyClose)

        // Close button immediately sends user to home
        btnClose.setOnClickListener {
            closeLockedAppAndGoHome()
        }

        // Tapping anywhere on the screen or card focuses the hidden keyboard
        view.setOnClickListener {
            focusSecretKeyboard(hiddenInput)
        }
        view.findViewById<View>(R.id.cardDecoy).setOnClickListener {
            focusSecretKeyboard(hiddenInput)
        }

        // Secret PIN input watcher - automatically unlocks when REAL PIN is typed
        hiddenInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable?) {
                val entered = s?.toString() ?: return
                if (entered.length >= 4) {
                    if (pinManager.check(entered) == PinManager.PinResult.REAL) {
                        unlockedPackages.add(targetPackage)
                        stopTimeoutTimer()
                        removeOverlay()
                    }
                }
            }
        })

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
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )

        try {
            windowManager?.addView(view, params)
            overlayView = view
            focusSecretKeyboard(hiddenInput)
            start15SecondsTimeout(timerText)
        } catch (e: Exception) {
            overlayShowingFor = null
        }
    }

    private fun focusSecretKeyboard(hiddenInput: EditText) {
        hiddenInput.requestFocus()
        hiddenInput.postDelayed({
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(hiddenInput, InputMethodManager.SHOW_IMPLICIT)
        }, 150)
    }

    private fun start15SecondsTimeout(timerTextView: TextView?) {
        stopTimeoutTimer()
        remainingSeconds = 15

        countdownRunnable = object : Runnable {
            override fun run() {
                remainingSeconds--
                timerTextView?.text = "Closing in ${remainingSeconds}s..."

                if (remainingSeconds <= 0) {
                    closeLockedAppAndGoHome()
                } else {
                    timeoutHandler.postDelayed(this, 1000)
                }
            }
        }
        timeoutHandler.postDelayed(countdownRunnable!!, 1000)
    }

    private fun stopTimeoutTimer() {
        countdownRunnable?.let { timeoutHandler.removeCallbacks(it) }
        countdownRunnable = null
    }

    private fun closeLockedAppAndGoHome() {
        stopTimeoutTimer()
        removeOverlay()

        // Send user back to launcher/home screen
        performGlobalAction(GLOBAL_ACTION_HOME)
    }

    private fun removeOverlay() {
        stopTimeoutTimer()
        overlayView?.let {
            try {
                windowManager?.removeView(it)
            } catch (e: Exception) {}
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
        val unlockedPackages = mutableSetOf<String>()
    }
}