package com.anas.applocker

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

class LockAccessibilityService : AccessibilityService() {

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var currentLockedPackage: String? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return

        // Ignore our own app and system keyboards so typing PIN does not dismiss lock screen
        if (pkg == packageName || isIgnoredSystemPackage(pkg)) return

        val store = LockedAppsStore(this)

        if (store.isLocked(pkg)) {
            if (!unlockedPackages.contains(pkg)) {
                if (currentLockedPackage != pkg) {
                    showLockOverlay(pkg)
                }
            }
        } else {
            // Only remove overlay if user actually navigated away to an unlocked app or launcher
            if (isLauncherOrHome(pkg) || (!store.isLocked(pkg) && !isIgnoredSystemPackage(pkg))) {
                unlockedPackages.remove(currentLockedPackage)
                removeOverlay()
            }
        }
    }

    private fun isIgnoredSystemPackage(pkg: String): Boolean {
        val lower = pkg.lowercase()
        return lower.contains("inputmethod") ||
            lower.contains("keyboard") ||
            lower.contains("systemui") ||
            lower == "android"
    }

    private fun isLauncherOrHome(pkg: String): Boolean {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolveInfo = packageManager.resolveActivity(intent, 0)
        return resolveInfo?.activityInfo?.packageName == pkg
    }

    private fun showLockOverlay(targetPackage: String) {
        val canDrawOverlay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else true

        if (!canDrawOverlay) {
            val intent = Intent(this, LockOverlayActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                putExtra(LockOverlayActivity.EXTRA_TARGET_PACKAGE, targetPackage)
            }
            startActivity(intent)
            return
        }

        removeOverlay()
        currentLockedPackage = targetPackage

        val inflater = LayoutInflater.from(this)
        val view = inflater.inflate(R.layout.activity_lock_overlay, null)

        val pinManager = PinManager(this)
        val fakeErrorCard = view.findViewById<LinearLayout>(R.id.fakeErrorCard)
        val secretPinLayout = view.findViewById<LinearLayout>(R.id.secretPinLayout)
        val warningIcon = view.findViewById<ImageView>(R.id.warningIcon)
        val warningTitle = view.findViewById<TextView>(R.id.warningTitle)
        val fakeErrorMessage = view.findViewById<TextView>(R.id.fakeErrorMessage)
        val btnFakeClose = view.findViewById<Button>(R.id.btnFakeClose)
        val btnFakeWait = view.findViewById<Button>(R.id.btnFakeWait)

        val secretPinInput = view.findViewById<EditText>(R.id.secretPinInput)
        val secretPinError = view.findViewById<TextView>(R.id.secretPinError)
        val btnSecretCancel = view.findViewById<Button>(R.id.btnSecretCancel)
        val btnSecretUnlock = view.findViewById<Button>(R.id.btnSecretUnlock)

        val appLabel = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(targetPackage, 0)).toString()
        } catch (e: Exception) {
            "This application"
        }

        fakeErrorMessage.text = "Device storage is critically low. \"$appLabel\" failed to allocate runtime memory and was suspended to prevent system instability.\n\nPlease free up internal storage and try again."

        // Clicking "Close App" or "Settings" kicks user to Home Screen safely
        val kickToHome = View.OnClickListener {
            goToHomeScreen()
            removeOverlay()
        }
        btnFakeClose.setOnClickListener(kickToHome)
        btnFakeWait.setOnClickListener(kickToHome)

        // SECRET TRIGGER: Long press OR 3 quick taps on warning icon/title
        var tapCount = 0
        var lastTapTime = 0L

        val triggerSecretPin: () -> Unit = {
            fakeErrorCard.visibility = View.GONE
            secretPinLayout.visibility = View.VISIBLE
            secretPinInput.requestFocus()
            handler.postDelayed({
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.showSoftInput(secretPinInput, InputMethodManager.SHOW_IMPLICIT)
            }, 100)
        }

        val secretClickListener = View.OnClickListener {
            val now = System.currentTimeMillis()
            if (now - lastTapTime < 500) {
                tapCount++
                if (tapCount >= 3) {
                    triggerSecretPin()
                    tapCount = 0
                }
            } else {
                tapCount = 1
            }
            lastTapTime = now
        }

        warningIcon.setOnClickListener(secretClickListener)
        warningTitle.setOnClickListener(secretClickListener)
        warningIcon.setOnLongClickListener { triggerSecretPin(); true }
        warningTitle.setOnLongClickListener { triggerSecretPin(); true }

        btnSecretCancel.setOnClickListener {
            secretPinInput.text.clear()
            secretPinError.visibility = View.INVISIBLE
            secretPinLayout.visibility = View.GONE
            fakeErrorCard.visibility = View.VISIBLE
        }

        btnSecretUnlock.setOnClickListener {
            val entered = secretPinInput.text.toString()
            if (pinManager.check(entered) == PinManager.PinResult.REAL) {
                unlockedPackages.add(targetPackage)
                removeOverlay()
            } else {
                secretPinError.text = "Authorization failed"
                secretPinError.visibility = View.VISIBLE
                secretPinInput.text.clear()
            }
        }

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        // FLAG_LAYOUT_IN_SCREEN & NO FLAG_NOT_TOUCH_MODAL ensures full barrier protection
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_FULLSCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        )

        try {
            windowManager?.addView(view, params)
            overlayView = view
        } catch (e: Exception) {
            currentLockedPackage = null
        }
    }

    private fun goToHomeScreen() {
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(homeIntent)
    }

    private fun removeOverlay() {
        overlayView?.let {
            try {
                windowManager?.removeView(it)
            } catch (e: Exception) { }
        }
        overlayView = null
        currentLockedPackage = null
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