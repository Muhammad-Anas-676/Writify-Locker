package com.anas.applocker

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
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
    private var isOverlayAttached = false
    private var currentLockedPackage: String? = null
    private val handler = Handler(Looper.getMainLooper())

    // Pre-bound View elements (No inflation lag on launch)
    private var fakeErrorCard: LinearLayout? = null
    private var secretPinLayout: LinearLayout? = null
    private var warningIcon: ImageView? = null
    private var warningTitle: TextView? = null
    private var fakeErrorMessage: TextView? = null
    private var secretPinInput: EditText? = null
    private var secretPinError: TextView? = null
    private var btnFakeClose: Button? = null
    private var btnFakeWait: Button? = null
    private var btnSecretCancel: Button? = null
    private var btnSecretUnlock: Button? = null

    private lateinit var pinManager: PinManager
    private lateinit var store: LockedAppsStore

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        pinManager = PinManager(this)
        store = LockedAppsStore(this)

        // Pre-inflate view in memory immediately so it's instantly ready
        prepareOverlayView()
    }

    private fun prepareOverlayView() {
        val inflater = LayoutInflater.from(this)
        overlayView = inflater.inflate(R.layout.activity_lock_overlay, null)

        overlayView?.let { view ->
            fakeErrorCard = view.findViewById(R.id.fakeErrorCard)
            secretPinLayout = view.findViewById(R.id.secretPinLayout)
            warningIcon = view.findViewById(R.id.warningIcon)
            warningTitle = view.findViewById(R.id.warningTitle)
            fakeErrorMessage = view.findViewById(R.id.fakeErrorMessage)
            secretPinInput = view.findViewById(R.id.secretPinInput)
            secretPinError = view.findViewById(R.id.secretPinError)
            btnFakeClose = view.findViewById(R.id.btnFakeClose)
            btnFakeWait = view.findViewById(R.id.btnFakeWait)
            btnSecretCancel = view.findViewById(R.id.btnSecretCancel)
            btnSecretUnlock = view.findViewById(R.id.btnSecretUnlock)

            val kickToHome = View.OnClickListener {
                goToHomeScreen()
                removeOverlay()
            }
            btnFakeClose?.setOnClickListener(kickToHome)
            btnFakeWait?.setOnClickListener(kickToHome)

            var tapCount = 0
            var lastTapTime = 0L

            val triggerSecretPin: () -> Unit = {
                fakeErrorCard?.visibility = View.GONE
                secretPinLayout?.visibility = View.VISIBLE
                secretPinInput?.requestFocus()
                handler.postDelayed({
                    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                    imm?.showSoftInput(secretPinInput, InputMethodManager.SHOW_IMPLICIT)
                }, 80)
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

            warningIcon?.setOnClickListener(secretClickListener)
            warningTitle?.setOnClickListener(secretClickListener)
            warningIcon?.setOnLongClickListener { triggerSecretPin(); true }
            warningTitle?.setOnLongClickListener { triggerSecretPin(); true }

            btnSecretCancel?.setOnClickListener {
                resetToFakeCard()
            }

            btnSecretUnlock?.setOnClickListener {
                val entered = secretPinInput?.text.toString()
                if (pinManager.check(entered) == PinManager.PinResult.REAL) {
                    currentLockedPackage?.let { unlockedPackages.add(it) }
                    removeOverlay()
                } else {
                    secretPinError?.text = "Authorization failed"
                    secretPinError?.visibility = View.VISIBLE
                    secretPinInput?.text?.clear()
                }
            }
        }
    }

    private fun resetToFakeCard() {
        secretPinInput?.text?.clear()
        secretPinError?.visibility = View.INVISIBLE
        secretPinLayout?.visibility = View.GONE
        fakeErrorCard?.visibility = View.VISIBLE
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return

        // Ignore our own app & system inputs
        if (pkg == packageName || isIgnoredSystemPackage(pkg)) return

        if (store.isLocked(pkg)) {
            if (!unlockedPackages.contains(pkg)) {
                if (currentLockedPackage != pkg || !isOverlayAttached) {
                    showLockOverlay(pkg)
                }
            }
        } else {
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
        currentLockedPackage = targetPackage

        if (overlayView == null) {
            prepareOverlayView()
        }

        resetToFakeCard()

        val appLabel = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(targetPackage, 0)).toString()
        } catch (e: Exception) {
            "This application"
        }

        fakeErrorMessage?.text = "Device storage is critically low. \"$appLabel\" failed to allocate runtime memory and was suspended to prevent system instability.\n\nPlease free up internal storage and try again."

        if (!isOverlayAttached && overlayView != null) {
            // TYPE_ACCESSIBILITY_OVERLAY provides instant, zero-delay rendering at maximum z-index
            val overlayType = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY

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
                windowManager?.addView(overlayView, params)
                isOverlayAttached = true
            } catch (e: Exception) {
                // Fallback in case of window manager issues
                isOverlayAttached = false
            }
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
        if (isOverlayAttached && overlayView != null) {
            try {
                windowManager?.removeView(overlayView)
            } catch (e: Exception) { }
            isOverlayAttached = false
        }
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