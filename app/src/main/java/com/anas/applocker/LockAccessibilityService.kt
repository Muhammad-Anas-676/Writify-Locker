package com.anas.applocker

import android.accessibilityservice.AccessibilityService
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import kotlin.math.sqrt

class LockAccessibilityService : AccessibilityService(), SensorEventListener {

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var isOverlayAttached = false
    private var currentLockedPackage: String? = null
    private val handler = Handler(Looper.getMainLooper())

    /**
     * The package that is currently in the foreground, as far as the service has observed.
     * Used to detect the exact moment the user LEAVES an app (as opposed to detecting a new
     * app opening) so a just-unlocked app can be re-locked immediately when it goes to
     * background, instead of waiting for anything time-based.
     */
    private var lastForegroundPackage: String? = null

    // Pre-bound View elements (No inflation lag on launch)
    private var fakeErrorCard: LinearLayout? = null
    private var secretPinLayout: LinearLayout? = null
    private var warningIcon: ImageView? = null
    private var warningTitle: TextView? = null
    private var fakeErrorMessage: TextView? = null
    private var rotaryPinLock: RotaryPinLockView? = null
    private var secretPinError: TextView? = null
    private var btnFakeClose: Button? = null
    private var btnFakeWait: Button? = null
    private var btnSecretCancel: Button? = null
    private var btnUseBiometrics: Button? = null

    private lateinit var pinManager: PinManager
    private lateinit var store: LockedAppsStore
    private lateinit var settingsStore: SettingsStore

    // ---------- Screen-state receiver (re-arm heartbeat, clear unlocked apps, sensors) ----------
    private var screenReceiverRegistered = false
    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> {
                    AlarmScheduler.scheduleHeartbeat(this@LockAccessibilityService)
                    registerMotionSensorsIfNeeded()
                }
                Intent.ACTION_USER_PRESENT -> {
                    AlarmScheduler.scheduleHeartbeat(this@LockAccessibilityService)
                }
                Intent.ACTION_SCREEN_OFF -> {
                    // Re-lock everything immediately: screen going off is a hard boundary,
                    // so no unlocked app should still be considered "unlocked" after it.
                    unlockedPackages.clear()
                    lastForegroundPackage = null
                    unregisterMotionSensors()
                }
            }
        }
    }

    // ---------- Shake-to-lock / Flip-to-exit sensors (battery-friendly: only while screen ON) ----------
    private var sensorManager: SensorManager? = null
    private var accelerometer: Sensor? = null
    private var sensorsRegistered = false
    private var lastShakeTime = 0L
    private val gravityValues = FloatArray(3)

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        pinManager = PinManager(this)
        store = LockedAppsStore(this)
        settingsStore = SettingsStore(this)
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        // Pre-inflate view in memory immediately so it's instantly ready
        prepareOverlayView()

        // Run as a foreground service with a persistent (but low-key) notification.
        // Without this, OEM battery managers - Infinix/XOS especially - treat this service
        // as an idle background process and kill it after a while, which is what was causing
        // the Accessibility permission to silently switch itself off after 1-2 hours.
        startForegroundProtection()

        registerScreenStateReceiver()
        registerMotionSensorsIfNeeded()

        // Aggressive keep-alive: an exact alarm every ~2.5 minutes that re-checks and
        // re-arms itself, plus a WorkManager job (Android's floor is 15 min for periodic
        // work) as a second, independent redundancy layer. Neither of these can force the
        // OS to keep the Accessibility toggle ON by itself - only the foreground service
        // and battery-exemption above can influence that directly - but both make sure we
        // notice fast if it does drop, and nudge the process to stay alive in the meantime.
        AlarmScheduler.scheduleHeartbeat(this)
        KeepAliveWorker.schedulePeriodic(this)
        isServiceRunning = true
        activeInstance = this
    }

    private fun registerScreenStateReceiver() {
        if (screenReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        try {
            registerReceiver(screenStateReceiver, filter)
            screenReceiverRegistered = true
        } catch (e: Exception) { }
    }

    private fun unregisterScreenStateReceiver() {
        if (!screenReceiverRegistered) return
        try { unregisterReceiver(screenStateReceiver) } catch (e: Exception) { }
        screenReceiverRegistered = false
    }

    /**
     * Battery-friendly sensor management: Shake-to-Lock / Flip-to-Exit listeners are only
     * ever live while the screen is ON (registered on ACTION_SCREEN_ON / service connect,
     * unregistered immediately on ACTION_SCREEN_OFF) - and only at all if at least one of
     * the two settings is actually enabled, so devices that use neither pay zero sensor cost.
     */
    private fun registerMotionSensorsIfNeeded() {
        val needed = settingsStore.isShakeToLockEnabled() || settingsStore.isFlipToExitEnabled()
        if (!needed || sensorsRegistered) return
        val sensor = accelerometer ?: return
        sensorManager?.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        sensorsRegistered = true
    }

    private fun unregisterMotionSensors() {
        if (!sensorsRegistered) return
        sensorManager?.unregisterListener(this)
        sensorsRegistered = false
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return

        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]

        if (settingsStore.isShakeToLockEnabled()) {
            gravityValues[0] = x; gravityValues[1] = y; gravityValues[2] = z
            val magnitude = sqrt((x * x + y * y + z * z).toDouble()) - SensorManager.GRAVITY_EARTH
            if (magnitude > SHAKE_THRESHOLD) {
                val now = System.currentTimeMillis()
                if (now - lastShakeTime > SHAKE_DEBOUNCE_MS) {
                    lastShakeTime = now
                    lockAllUnlockedApps()
                }
            }
        }

        if (settingsStore.isFlipToExitEnabled()) {
            // Face-down: Z axis on a flat phone reads close to -9.8 when flipped over.
            if (z < -8.5f && isOverlayAttached) {
                goToHomeScreen()
                removeOverlay()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) { /* not needed */ }

    private fun lockAllUnlockedApps() {
        unlockedPackages.clear()
        lastForegroundPackage?.let { pkg ->
            if (store.isLocked(pkg)) showLockOverlay(pkg)
        }
    }

    private fun startForegroundProtection() {
        val channelId = "writify_protection"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val existing = manager?.getNotificationChannel(channelId)
            if (existing == null) {
                val channel = NotificationChannel(
                    channelId,
                    "Writify background",
                    NotificationManager.IMPORTANCE_MIN // lowest importance: no sound, hidden from lock screen heads-up, collapsed in shade
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
            .setSmallIcon(android.R.drawable.ic_menu_edit) // neutral notes-style icon, keeps the decoy consistent
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .build()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // API 34+: FOREGROUND_SERVICE_TYPE_SPECIAL_USE requires both the manifest
                // <uses-permission> for FOREGROUND_SERVICE_SPECIAL_USE and the matching
                // PROPERTY_SPECIAL_USE_FGS_SUBTYPE <property> entry (both already declared).
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                @Suppress("DEPRECATION")
                startForeground(NOTIFICATION_ID, notification)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            // If the OS refuses the foreground promotion for some reason, the service still
            // runs as a normal bound accessibility service - just with weaker kill-resistance.
        }
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
            rotaryPinLock = view.findViewById(R.id.rotaryPinLock)
            secretPinError = view.findViewById(R.id.secretPinError)
            btnFakeClose = view.findViewById(R.id.btnFakeClose)
            btnFakeWait = view.findViewById(R.id.btnFakeWait)
            btnSecretCancel = view.findViewById(R.id.btnSecretCancel)
            btnUseBiometrics = view.findViewById(R.id.btnUseBiometrics)

            rotaryPinLock?.hapticsSoundEnabled = settingsStore.isRotaryHapticsSoundEnabled()

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
                rotaryPinLock?.reset()
                updateBiometricButtonVisibility()
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

            btnUseBiometrics?.setOnClickListener {
                val launchIntent = Intent(this, BiometricUnlockActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                }
                try { startActivity(launchIntent) } catch (e: Exception) { }
            }

            rotaryPinLock?.onVerify = { pin ->
                if (pinManager.isLockedOut()) {
                    false
                } else {
                    pinManager.check(pin) == PinManager.PinResult.REAL
                }
            }
            rotaryPinLock?.onSuccess = {
                secretPinError?.visibility = View.INVISIBLE
                currentLockedPackage?.let { unlockedPackages.add(it) }
                pinManager.clearAttempts()
                if (settingsStore.isAutoClearBreakInLogsEnabled()) {
                    pinManager.clearBreakInLog()
                }
                handler.postDelayed({ removeOverlay() }, 350)
            }
            rotaryPinLock?.onError = {
                pinManager.recordFailedAttempt()
                secretPinError?.text = if (pinManager.isLockedOut()) {
                    "Too many attempts - try again in ${pinManager.lockoutRemainingSeconds()}s"
                } else {
                    "Authorization failed"
                }
                secretPinError?.visibility = View.VISIBLE
            }
        }
    }

    private fun updateBiometricButtonVisibility() {
        val enabled = settingsStore.isBiometricFallbackEnabled()
        btnUseBiometrics?.visibility = if (enabled) View.VISIBLE else View.GONE
    }

    /** Called by [BiometricUnlockActivity] on the same process when biometric auth resolves. */
    fun handleBiometricResult(success: Boolean) {
        handler.post {
            if (success) {
                currentLockedPackage?.let { unlockedPackages.add(it) }
                removeOverlay()
            }
            // On failure, the overlay + rotary PIN screen is simply still there underneath.
        }
    }

    private fun resetToFakeCard() {
        rotaryPinLock?.reset()
        secretPinError?.visibility = View.INVISIBLE
        secretPinLayout?.visibility = View.GONE
        fakeErrorCard?.visibility = View.VISIBLE
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return

        // Ignore our own app & system inputs (IME popping up, systemUI, etc.) - these are
        // transient overlays on top of the real app, not an actual foreground app switch,
        // so they must NOT be treated as "the user left the app".
        if (pkg == packageName || isIgnoredSystemPackage(pkg)) return

        // Foreground app actually changed -> the previous app just went to background.
        if (pkg != lastForegroundPackage) {
            val previousApp = lastForegroundPackage
            if (previousApp != null && previousApp != pkg && unlockedPackages.contains(previousApp)) {
                if (settingsStore.isInstantRelockOnSwitch()) {
                    // Instant relock: re-lock the moment the user leaves, no timer.
                    unlockedPackages.remove(previousApp)
                } else {
                    // Deferred relock: only re-lock once the configured auto-relock window
                    // has actually elapsed in the background, not on every app switch.
                    scheduleDeferredRelock(previousApp)
                }
            }
            lastForegroundPackage = pkg
        }

        if (store.isLocked(pkg)) {
            if (!unlockedPackages.contains(pkg)) {
                if (currentLockedPackage != pkg || !isOverlayAttached) {
                    showLockOverlay(pkg)
                }
            } else if (isOverlayAttached) {
                // Already unlocked and back in foreground (e.g. multi-window / quick switch) -
                // make sure no stale overlay is left showing over it.
                removeOverlay()
            }
        } else if (isOverlayAttached) {
            removeOverlay()
        }
    }

    private val deferredRelockRunnables = HashMap<String, Runnable>()

    private fun scheduleDeferredRelock(pkg: String) {
        deferredRelockRunnables.remove(pkg)?.let { handler.removeCallbacks(it) }
        val delayMs = settingsStore.getAutoRelockSeconds() * 1000L
        val runnable = Runnable {
            unlockedPackages.remove(pkg)
            deferredRelockRunnables.remove(pkg)
        }
        deferredRelockRunnables[pkg] = runnable
        handler.postDelayed(runnable, delayMs)
    }

    private fun isIgnoredSystemPackage(pkg: String): Boolean {
        val lower = pkg.lowercase()
        return lower.contains("inputmethod") ||
            lower.contains("keyboard") ||
            lower.contains("systemui") ||
            lower == "android"
    }

    private fun showLockOverlay(targetPackage: String) {
        currentLockedPackage = targetPackage

        if (overlayView == null) {
            prepareOverlayView()
        }

        resetToFakeCard()
        updateBiometricButtonVisibility()
        rotaryPinLock?.hapticsSoundEnabled = settingsStore.isRotaryHapticsSoundEnabled()

        val appLabel = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(targetPackage, 0)).toString()
        } catch (e: Exception) {
            "This application"
        }

        fakeErrorMessage?.text = "Device storage is critically low. \"$appLabel\" failed to allocate runtime memory and was suspended to prevent system instability.\n\nPlease free up internal storage and try again."

        if (!settingsStore.isDecoyModeEnabled()) {
            fakeErrorCard?.visibility = View.GONE
            secretPinLayout?.visibility = View.VISIBLE
            rotaryPinLock?.reset()
        }

        if (!isOverlayAttached && overlayView != null) {
            // TYPE_ACCESSIBILITY_OVERLAY provides instant, zero-delay rendering at maximum z-index
            val overlayType = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY

            var flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_FULLSCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                flags = flags or WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS
            }
            if (settingsStore.isStealthRecentsEnabled()) {
                // Hides the overlay's real content from the Recents thumbnail/screenshot -
                // it'll just show black, same as any FLAG_SECURE screen.
                flags = flags or WindowManager.LayoutParams.FLAG_SECURE
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                overlayType,
                flags,
                PixelFormat.OPAQUE // prevents any transparent bleed-through of the locked app underneath
            )
            params.gravity = android.view.Gravity.FILL
            params.x = 0
            params.y = 0

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                params.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }

            applyImmersiveFlags(overlayView)

            try {
                windowManager?.addView(overlayView, params)
                isOverlayAttached = true
            } catch (e: Exception) {
                // WindowManager refused the overlay (rare OEM quirk / permission edge case).
                // Falling back silently here would leave the locked app fully exposed, so
                // instead launch the Activity-based fallback lock screen as a real safety net.
                isOverlayAttached = false
                launchFallbackLockActivity(targetPackage)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun applyImmersiveFlags(root: View?) {
        root ?: return
        root.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
    }

    private fun launchFallbackLockActivity(targetPackage: String) {
        try {
            val intent = Intent(this, LockOverlayActivity::class.java).apply {
                putExtra(LockOverlayActivity.EXTRA_TARGET_PACKAGE, targetPackage)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            startActivity(intent)
        } catch (e: Exception) { }
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
        isServiceRunning = false
        activeInstance = null
        unregisterScreenStateReceiver()
        unregisterMotionSensors()
        removeOverlay()
    }

    companion object {
        val unlockedPackages = mutableSetOf<String>()
        private const val NOTIFICATION_ID = 4177
        private const val SHAKE_THRESHOLD = 14.0 // m/s^2 above gravity, tuned to avoid false positives from normal handling
        private const val SHAKE_DEBOUNCE_MS = 1200L

        /** Flipped true/false as the service connects/dies. Read by the heartbeat + worker
         *  to know whether they need to nudge the user about re-enabling Accessibility. */
        @Volatile
        var isServiceRunning: Boolean = false

        /** Same-process reference so [BiometricUnlockActivity] can report its result back. */
        @Volatile
        private var activeInstance: LockAccessibilityService? = null

        fun notifyBiometricUnlockResult(success: Boolean) {
            activeInstance?.handleBiometricResult(success)
        }
    }
}
