package com.anas.applocker

import android.accessibilityservice.AccessibilityService
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.graphics.PixelFormat
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

class LockAccessibilityService : AccessibilityService() {

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

    private lateinit var pinManager: PinManager
    private lateinit var store: LockedAppsStore
    private lateinit var settingsStore: SettingsStore

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        pinManager = PinManager(this)
        store = LockedAppsStore(this)
        settingsStore = SettingsStore(this)

        // Pre-inflate view in memory immediately so it's instantly ready
        prepareOverlayView()

        // Run as a foreground service with a persistent (but low-key) notification.
        // Without this, OEM battery managers - Infinix/XOS especially - treat this service
        // as an idle background process and kill it after a while, which is what was causing
        // the Accessibility permission to silently switch itself off after 1-2 hours.
        startForegroundProtection()

        // Aggressive keep-alive: an exact alarm every ~2.5 minutes that re-checks and
        // re-arms itself, plus a WorkManager job (Android's floor is 15 min for periodic
        // work) as a second, independent redundancy layer. Neither of these can force the
        // OS to keep the Accessibility toggle ON by itself - only the foreground service
        // and battery-exemption above can influence that directly - but both make sure we
        // notice fast if it does drop, and nudge the process to stay alive in the meantime.
        AlarmScheduler.scheduleHeartbeat(this)
        KeepAliveWorker.schedulePeriodic(this)
        isServiceRunning = true
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
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

            rotaryPinLock?.onVerify = { pin -> pinManager.check(pin) == PinManager.PinResult.REAL }
            rotaryPinLock?.onSuccess = {
                secretPinError?.visibility = View.INVISIBLE
                currentLockedPackage?.let { unlockedPackages.add(it) }
                handler.postDelayed({ removeOverlay() }, 350)
            }
            rotaryPinLock?.onError = {
                secretPinError?.text = "Authorization failed"
                secretPinError?.visibility = View.VISIBLE
            }
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
        // If that previous app was an unlocked locked-app, re-lock it immediately right here,
        // instead of relying on any timer. This is what makes the re-lock instant instead of
        // waiting ~1 hour: previously the code cleared the wrong variable (currentLockedPackage,
        // which had already been reset to null right after unlocking) so the unlocked package
        // never actually got removed from the unlocked set until the whole service restarted.
        if (pkg != lastForegroundPackage) {
            val previousApp = lastForegroundPackage
            if (previousApp != null && previousApp != pkg) {
                unlockedPackages.remove(previousApp)
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
        isServiceRunning = false
        removeOverlay()
    }

    companion object {
        val unlockedPackages = mutableSetOf<String>()
        private const val NOTIFICATION_ID = 4177

        /** Flipped true/false as the service connects/dies. Read by the heartbeat + worker
         *  to know whether they need to nudge the user about re-enabling Accessibility. */
        @Volatile
        var isServiceRunning: Boolean = false
    }
}