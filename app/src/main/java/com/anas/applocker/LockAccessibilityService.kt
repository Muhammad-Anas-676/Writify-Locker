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

class LockAccessibilityService : AccessibilityService() {

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var overlayShowingFor: String? = null

    private val timerHandler = Handler(Looper.getMainLooper())
    private var secondsLeft = 15
    private var timerRunnable: Runnable? = null

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
            // Foreground moved to something ordinary (launcher, non-locked app)
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
        val secretInput = view.findViewById<EditText>(R.id.secretPinInput)
        val timerText = view.findViewById<TextView>(R.id.countdownTimerText)
        val closeButton = view.findViewById<Button>(R.id.btnDecoyClose)

        // Close button: Closes app and returns to Home
        closeButton.setOnClickListener {
            dismissAndGoHome()
        }

        // Tapping on the screen/dialog opens the keyboard for secret typing
        val showKeyboardListener = View.OnClickListener {
            secretInput.requestFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(secretInput, InputMethodManager.SHOW_IMPLICIT)
        }
        view.setOnClickListener(showKeyboardListener)
        view.findViewById<View>(R.id.cardDecoy).setOnClickListener(showKeyboardListener)

        // Hidden PIN Auto-Checker
        secretInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable?) {
                val entered = s?.toString() ?: return
                if (entered.length >= 4) {
                    if (pinManager.check(entered) == PinManager.PinResult.REAL) {
                        unlockedPackages.add(targetPackage)
                        stopTimer()
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
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )

        try {
            windowManager?.addView(view, params)
            overlayView = view
            
            // Auto focus secret input
            secretInput.requestFocus()
            start15SecondsTimer(timerText)
        } catch (e: Exception) {
            overlayShowingFor = null
        }
    }

    private fun start15SecondsTimer(timerView: TextView?) {
        stopTimer()
        secondsLeft = 15

        timerRunnable = object : Runnable {
            override fun run() {
                secondsLeft--
                timerView?.text = "Closing in ${secondsLeft}s..."

                if (secondsLeft <= 0) {
                    dismissAndGoHome()
                } else {
                    timerHandler.postDelayed(this, 1000)
                }
            }
        }
        timerHandler.postDelayed(timerRunnable!!, 1000)
    }

    private fun stopTimer() {
        timerRunnable?.let { timerHandler.removeCallbacks(it) }
        timerRunnable = null
    }

    private fun dismissAndGoHome() {
        stopTimer()
        removeOverlay()
        performGlobalAction(GLOBAL_ACTION_HOME)
    }

    private fun removeOverlay() {
        stopTimer()
        overlayView?.let {
            try {
                windowManager?.removeView(it)
            } catch (e: Exception) {
                // Safe ignore
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
        val unlockedPackages = mutableSetOf<String>()
    }
}