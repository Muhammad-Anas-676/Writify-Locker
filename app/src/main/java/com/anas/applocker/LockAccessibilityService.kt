package com.anas.applocker

import android.accessibilityservice.AccessibilityService
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentName
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
import androidx.core.app.NotificationCompat

/**
 * Core accessibility service — runs 24/7 as a foreground service with
 * [FOREGROUND_SERVICE_TYPE_SPECIAL_USE] and powers three protection layers:
 *
 *  1. APP LOCK — shows the decoy storage-error overlay whenever a locked app opens.
 *
 *  2. ANTI-UNINSTALL / SETTINGS PROTECTION — intercepts:
 *       • Package-installer packages (all vendors).
 *       • Settings screens that manage apps (App Info, Uninstall, Force-Stop, Device Admin pages).
 *       • SystemUI Recents when our task may be swiped away (API 28+ WINDOWS_CHANGE_REMOVED).
 *     Displays the protection overlay with "You cannot perform this action until you enter
 *     the correct authorization key."
 *
 *  3. INVERTED DECOY AUTH — both overlay modes use the wrong keyboard type initially:
 *       • Real = ALPHABETIC  →  overlay shows NUMERIC keypad.
 *       • Real = NUMERIC     →  overlay shows QWERTY keyboard.
 *     A 3-tap rapid sequence OR long-press on the warning icon/title triggers [triggerSecretPanel],
 *     which calls [InputMethodManager.restartInput] to flip to the real keyboard type.
 *     Successful auth dismisses the overlay; failed auth shows an error and resets the field.
 *     Dismissing without auth sends the user back to the Home screen.
 */
class LockAccessibilityService : AccessibilityService() {

    // ──────────────────────────────────────────────────────────────
    // Window manager + overlay state
    // ──────────────────────────────────────────────────────────────

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var isOverlayAttached = false
    private var overlayMode: OverlayMode = OverlayMode.APP_LOCK

    /** Package currently locked behind the APP_LOCK overlay. */
    private var currentLockedPackage: String? = null
    /** Package that triggered the PROTECTION overlay (or RECENTS_TASK_KEY for task removal). */
    private var currentProtectedActionPackage: String? = null

    // Inverted-decoy state
    private var isKeyboardFlippedToReal = false

    // Recents-swipe tracking
    private var isRecentsForeground = false

    /** The package name that was in the foreground just before a SystemUI event. */
    private var lastForegroundPackage: String? = null

    private val handler = Handler(Looper.getMainLooper())

    // Pre-bound overlay views (no inflation lag on first lock)
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

    // ──────────────────────────────────────────────────────────────
    // Service lifecycle
    // ──────────────────────────────────────────────────────────────

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        pinManager    = PinManager(this)
        store         = LockedAppsStore(this)

        prepareOverlayView()
        startForegroundProtection()
    }

    /**
     * Promotes the service to foreground with a minimal, silent notification.
     * Without this, OEM battery managers (Infinix XOS especially) kill the service
     * after a few hours, which also silently flips the Accessibility toggle off.
     */
    private fun startForegroundProtection() {
        val channelId = "writify_protection"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            if (manager?.getNotificationChannel(channelId) == null) {
                val channel = NotificationChannel(
                    channelId,
                    "Writify background",
                    NotificationManager.IMPORTANCE_MIN
                ).apply {
                    setShowBadge(false)
                    description = "Keeps Writify running in the background"
                }
                manager?.createNotificationChannel(channel)
            }
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Writify")
            .setContentText("Running")
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .build()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (_: Exception) {
            // Service still runs as a bound accessibility service if OS rejects promotion.
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Overlay inflation & wiring
    // ──────────────────────────────────────────────────────────────

    private fun prepareOverlayView() {
        overlayView = LayoutInflater.from(this).inflate(R.layout.activity_lock_overlay, null)

        overlayView?.let { view ->
            fakeErrorCard    = view.findViewById(R.id.fakeErrorCard)
            secretPinLayout  = view.findViewById(R.id.secretPinLayout)
            warningIcon      = view.findViewById(R.id.warningIcon)
            warningTitle     = view.findViewById(R.id.warningTitle)
            fakeErrorMessage = view.findViewById(R.id.fakeErrorMessage)
            secretPinInput   = view.findViewById(R.id.secretPinInput)
            secretPinError   = view.findViewById(R.id.secretPinError)
            btnFakeClose     = view.findViewById(R.id.btnFakeClose)
            btnFakeWait      = view.findViewById(R.id.btnFakeWait)
            btnSecretCancel  = view.findViewById(R.id.btnSecretCancel)
            btnSecretUnlock  = view.findViewById(R.id.btnSecretUnlock)

            // Dismissing the decoy card without auth → kick to Home
            val kickToHome = View.OnClickListener {
                goToHomeScreen()
                removeOverlay()
            }
            btnFakeClose?.setOnClickListener(kickToHome)
            btnFakeWait?.setOnClickListener(kickToHome)

            // ── Secret trigger: 3 rapid taps or long-press on warning elements ──
            var tapCount   = 0
            var lastTapMs  = 0L

            val triggerSecretPanel: () -> Unit = {
                showSecretPanel()
            }

            val secretTapListener = View.OnClickListener {
                val now = System.currentTimeMillis()
                if (now - lastTapMs < 500) {
                    tapCount++
                    if (tapCount >= 3) { triggerSecretPanel(); tapCount = 0 }
                } else {
                    tapCount = 1
                }
                lastTapMs = now
            }

            warningIcon?.setOnClickListener(secretTapListener)
            warningTitle?.setOnClickListener(secretTapListener)
            warningIcon?.setOnLongClickListener { triggerSecretPanel(); true }
            warningTitle?.setOnLongClickListener { triggerSecretPanel(); true }

            // Back button resets to decoy card
            btnSecretCancel?.setOnClickListener { resetToDecoyCard() }

            // Verify button — check the entered credential
            btnSecretUnlock?.setOnClickListener { verifyCredential() }
        }
    }

    /**
     * Shows the hidden credential panel and flips the keyboard to the DECOY type initially.
     * The keyboard flips to the REAL type only after [triggerKeyboardFlip] is called again
     * (which happens inside [showSecretPanel] — the panel reveals THEN the gesture flips it).
     *
     * Flow:
     *   1. Decoy card visible    → user taps 3× or long-presses icon/title
     *   2. Secret panel visible  → still shows DECOY keyboard (wrong type)
     *   3. User taps 3× again   → [triggerKeyboardFlip] → real keyboard type
     *   4. User enters credential → auth check
     *
     * Note: showing the panel already sets the DECOY keyboard. The trigger gesture must be
     * performed AGAIN on the visible secret panel if the user wants to flip to the real type.
     * This double-trigger design means a casual snooper who finds the gesture won't immediately
     * get the right keyboard — they'd have to know to trigger it twice.
     */
    private fun showSecretPanel() {
        isKeyboardFlippedToReal = false
        // Set to DECOY type initially
        secretPinInput?.inputType = pinManager.getDecoyInputType()

        fakeErrorCard?.visibility   = View.GONE
        secretPinLayout?.visibility = View.VISIBLE
        secretPinInput?.requestFocus()

        // Rewire the trigger: inside the secret panel the SAME gesture flips to the real keyboard
        var innerTapCount = 0
        var innerLastTap  = 0L

        val flipToReal = View.OnClickListener {
            val now = System.currentTimeMillis()
            if (now - innerLastTap < 500) {
                innerTapCount++
                if (innerTapCount >= 3) { triggerKeyboardFlip(); innerTapCount = 0 }
            } else {
                innerTapCount = 1
            }
            innerLastTap = now
        }

        // In the secret panel the icon/title tap listener flips the keyboard
        warningIcon?.setOnClickListener(flipToReal)
        warningTitle?.setOnClickListener(flipToReal)
        warningIcon?.setOnLongClickListener  { triggerKeyboardFlip(); true }
        warningTitle?.setOnLongClickListener { triggerKeyboardFlip(); true }

        handler.postDelayed({
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(secretPinInput, InputMethodManager.SHOW_IMPLICIT)
        }, 80)
    }

    /**
     * Flips the credential EditText to the REAL InputType and restarts the soft keyboard
     * so the correct keyboard layout is drawn immediately (per spec: [InputMethodManager.restartInput]).
     */
    private fun triggerKeyboardFlip() {
        if (isKeyboardFlippedToReal) return
        isKeyboardFlippedToReal = true

        val input = secretPinInput ?: return
        input.inputType = pinManager.getRealInputType()

        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.restartInput(input)
        imm?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun verifyCredential() {
        val entered = secretPinInput?.text?.toString() ?: ""
        if (pinManager.check(entered) == PinManager.PinResult.REAL) {
            when (overlayMode) {
                OverlayMode.APP_LOCK -> {
                    // Whitelist this package so the overlay doesn't reappear immediately
                    currentLockedPackage?.let { unlockedPackages.add(it) }
                }
                OverlayMode.PROTECTION -> {
                    // Whitelist the admin action so the service ignores the next event from it
                    currentProtectedActionPackage?.let { adminWhitelistedPackages.add(it) }
                }
            }
            removeOverlay()
            restoreDecoyTriggerListeners() // reset tap listeners for next use
        } else {
            pinManager.recordFailedAttempt()
            secretPinError?.text = "Authorization failed"
            secretPinError?.visibility = View.VISIBLE
            secretPinInput?.text?.clear()
        }
    }

    /** Restore the decoy card tap listeners (overwritten by the secret panel's flip listeners). */
    private fun restoreDecoyTriggerListeners() {
        var tapCount  = 0
        var lastTapMs = 0L
        val listener  = View.OnClickListener {
            val now = System.currentTimeMillis()
            if (now - lastTapMs < 500) { tapCount++; if (tapCount >= 3) { showSecretPanel(); tapCount = 0 } }
            else tapCount = 1
            lastTapMs = now
        }
        warningIcon?.setOnClickListener(listener)
        warningTitle?.setOnClickListener(listener)
        warningIcon?.setOnLongClickListener  { showSecretPanel(); true }
        warningTitle?.setOnLongClickListener { showSecretPanel(); true }
    }

    private fun resetToDecoyCard() {
        secretPinInput?.text?.clear()
        secretPinError?.visibility = View.INVISIBLE
        secretPinLayout?.visibility = View.GONE
        fakeErrorCard?.visibility   = View.VISIBLE
        isKeyboardFlippedToReal = false
        restoreDecoyTriggerListeners()
    }

    // ──────────────────────────────────────────────────────────────
    // Accessibility event handling
    // ──────────────────────────────────────────────────────────────

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg       = event?.packageName?.toString() ?: return
        val eventType = event.eventType
        val className = event.className?.toString() ?: ""

        // Never react to our own package
        if (pkg == packageName) return

        // ── 1. RECENTS TASK-REMOVAL DETECTION (API 28+) ────────────────────────
        // When our app was the last foreground package and Recents is now visible, a
        // WINDOWS_CHANGE_REMOVED event signals that a task window was swept away.
        // This fires when the user swipes our task card off the Recents screen.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED &&
            event.windowChanges and AccessibilityEvent.WINDOWS_CHANGE_REMOVED != 0 &&
            isRecentsForeground &&
            lastForegroundPackage == packageName &&
            !adminWhitelistedPackages.contains(RECENTS_TASK_KEY)
        ) {
            showProtectionOverlay(RECENTS_TASK_KEY)
            return
        }

        // Track whether SystemUI Recents is currently shown
        if (pkg == PACKAGE_SYSTEMUI) {
            if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                isRecentsForeground = true
            }
        } else if (isRecentsForeground && !isIgnoredSystemPackage(pkg)) {
            // User navigated away from Recents to a real app
            isRecentsForeground = false
            adminWhitelistedPackages.remove(RECENTS_TASK_KEY)
        }

        // ── 2. SETTINGS / UNINSTALLER PROTECTION ───────────────────────────────
        // Block Settings danger screens (App Info, Uninstall, Force Stop, Device Admin) and all
        // package-installer packages — unless the user has already authenticated this action.
        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            !adminWhitelistedPackages.contains(pkg) &&
            shouldProtectAgainstAction(pkg, className)
        ) {
            showProtectionOverlay(pkg)
            return
        }

        // ── 3. Skip system-UI packages for foreground tracking ──────────────────
        if (isIgnoredSystemPackage(pkg)) return

        // ── 4. Foreground-tracking + re-lock on app-switch ─────────────────────
        // When the foreground app changes, the previous app leaves. If it was an unlocked
        // locked-app, remove it from the whitelist immediately (instant re-lock on switch).
        if (pkg != lastForegroundPackage) {
            val previousApp = lastForegroundPackage
            if (previousApp != null && previousApp != pkg) {
                unlockedPackages.remove(previousApp)
                // Clear admin whitelist for the package that just went to background
                adminWhitelistedPackages.remove(previousApp)
            }
            lastForegroundPackage = pkg
        }

        // ── 5. APP LOCK ─────────────────────────────────────────────────────────
        if (store.isLocked(pkg)) {
            if (!unlockedPackages.contains(pkg)) {
                if (currentLockedPackage != pkg || !isOverlayAttached) {
                    showLockOverlay(pkg)
                }
            } else if (isOverlayAttached && overlayMode == OverlayMode.APP_LOCK) {
                // Already unlocked and returned to foreground — remove any stale overlay
                removeOverlay()
            }
        } else if (isOverlayAttached && overlayMode == OverlayMode.APP_LOCK) {
            removeOverlay()
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Protection-trigger evaluation
    // ──────────────────────────────────────────────────────────────

    /**
     * Returns true when this package + class name indicates an action that should be
     * blocked until the user authenticates via the protection overlay.
     *
     * Covers:
     *  (a) Any package-installer / uninstaller package (all major OEM variants).
     *  (b) Settings navigating to app management, uninstall, force-stop, or device-admin pages.
     */
    private fun shouldProtectAgainstAction(pkg: String, className: String): Boolean {
        // All known package-installer variants always trigger protection
        if (pkg in UNINSTALL_PACKAGES) return true

        // Settings danger screens detected by activity class-name keywords
        if (pkg == PACKAGE_SETTINGS) {
            return SETTINGS_DANGER_KEYWORDS.any { className.contains(it, ignoreCase = true) }
        }

        return false
    }

    private fun isIgnoredSystemPackage(pkg: String): Boolean {
        val lower = pkg.lowercase()
        return lower.contains("inputmethod") ||
            lower.contains("keyboard") ||
            lower.contains("systemui") ||
            lower == "android"
    }

    // ──────────────────────────────────────────────────────────────
    // Overlay display
    // ──────────────────────────────────────────────────────────────

    private fun showLockOverlay(targetPackage: String) {
        currentLockedPackage = targetPackage
        overlayMode = OverlayMode.APP_LOCK

        if (overlayView == null) prepareOverlayView()
        resetToDecoyCard()

        // Fake storage-error message personalised to the app being locked
        val appLabel = try {
            packageManager.getApplicationLabel(
                packageManager.getApplicationInfo(targetPackage, 0)
            ).toString()
        } catch (_: Exception) { "This application" }

        warningTitle?.text     = "System Storage Critical"
        fakeErrorMessage?.text =
            "Device storage is critically low. \"$appLabel\" failed to allocate runtime memory " +
            "and was suspended to prevent system instability.\n\nPlease free up internal storage and try again."

        attachOverlay()
    }

    private fun showProtectionOverlay(actionPackage: String) {
        currentProtectedActionPackage = actionPackage
        overlayMode = OverlayMode.PROTECTION

        if (overlayView == null) prepareOverlayView()
        resetToDecoyCard()

        // Per spec: "You cannot perform this action until you enter the correct authorization key."
        warningTitle?.text     = "Action Blocked"
        fakeErrorMessage?.text =
            "You cannot perform this action until you enter the correct authorization key."

        attachOverlay()
    }

    private fun attachOverlay() {
        if (isOverlayAttached || overlayView == null) return

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_FULLSCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        )

        try {
            windowManager?.addView(overlayView, params)
            isOverlayAttached = true
        } catch (_: Exception) {
            isOverlayAttached = false
        }
    }

    private fun removeOverlay() {
        if (isOverlayAttached && overlayView != null) {
            try { windowManager?.removeView(overlayView) } catch (_: Exception) { }
            isOverlayAttached = false
        }
        currentLockedPackage = null
        currentProtectedActionPackage = null
    }

    private fun goToHomeScreen() {
        startActivity(Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        })
    }

    // ──────────────────────────────────────────────────────────────
    // Service teardown
    // ──────────────────────────────────────────────────────────────

    override fun onInterrupt() { removeOverlay() }

    override fun onDestroy() {
        super.onDestroy()
        removeOverlay()
    }

    // ──────────────────────────────────────────────────────────────
    // Shared state
    // ──────────────────────────────────────────────────────────────

    /** Packages the user has unlocked for this foreground session. Cleared on app-switch. */
    enum class OverlayMode { APP_LOCK, PROTECTION }

    companion object {
        val unlockedPackages        = mutableSetOf<String>()
        val adminWhitelistedPackages = mutableSetOf<String>()

        private const val NOTIFICATION_ID   = 4177
        private const val PACKAGE_SETTINGS  = "com.android.settings"
        private const val PACKAGE_SYSTEMUI  = "com.android.systemui"
        const val         RECENTS_TASK_KEY  = "__recents_task__"

        /** Package-installer variants across major OEMs / AOSP. */
        private val UNINSTALL_PACKAGES = setOf(
            "com.google.android.packageinstaller",
            "com.android.packageinstaller",
            "com.miui.packageinstaller",
            "com.samsung.android.packageinstaller",
            "com.transsion.packageinstaller",
            "com.zte.packageinstaller",
            "com.oppo.installer",
            "com.coloros.packageinstaller",
            "com.vivo.packageinstaller",
        )

        /**
         * Class-name substrings in com.android.settings that indicate the user has navigated to
         * an app management screen. Match is case-insensitive against [AccessibilityEvent.className].
         *
         * Examples (API-level / OEM variations exist):
         *   InstalledAppDetailsActivity, AppInfoDashboardFragment, DeviceAdminAdd,
         *   UninstallActivity, AppForceStopPreferenceController, DeviceAdminSettings, …
         */
        private val SETTINGS_DANGER_KEYWORDS = listOf(
            "AppDetails",
            "InstalledAppDetails",
            "AppInfo",
            "ManageApp",
            "UninstallActivity",
            "AppDetailSettings",
            "ApplicationDetail",
            "DeviceAdmin",
            "ForceStop",
            "AppForceStop",
        )
    }
}
