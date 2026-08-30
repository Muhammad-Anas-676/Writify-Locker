package com.anas.applocker

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Activity-based fallback lock screen. Normally the AccessibilityService draws the lock
 * screen as a TYPE_ACCESSIBILITY_OVERLAY window directly, which is instant and needs no
 * activity transition - this class only gets launched as a safety net for the rare case
 * where WindowManager.addView() on that overlay throws (some OEM launchers/skins restrict
 * accessibility overlays under certain conditions). Without this fallback, that failure
 * would leave a locked app fully exposed with no lock screen at all.
 */
class LockOverlayActivity : AppCompatActivity() {

    private lateinit var pinManager: PinManager
    private lateinit var settingsStore: SettingsStore
    private var targetPackage: String? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        settingsStore = SettingsStore(this)

        // Same screen-leak protections as the accessibility overlay: full-bleed under
        // cutouts/nav bar, and stealth-recents if the user has that setting on.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        if (settingsStore.isStealthRecentsEnabled()) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        }
        applyImmersiveFlags()

        setContentView(R.layout.activity_lock_overlay)

        pinManager = PinManager(this)
        targetPackage = intent.getStringExtra(EXTRA_TARGET_PACKAGE)

        val fakeErrorCard = findViewById<LinearLayout>(R.id.fakeErrorCard)
        val secretPinLayout = findViewById<LinearLayout>(R.id.secretPinLayout)
        val warningIcon = findViewById<ImageView>(R.id.warningIcon)
        val warningTitle = findViewById<TextView>(R.id.warningTitle)
        val fakeErrorMessage = findViewById<TextView>(R.id.fakeErrorMessage)
        val btnFakeClose = findViewById<Button>(R.id.btnFakeClose)
        val btnFakeWait = findViewById<Button>(R.id.btnFakeWait)

        val rotaryPinLock = findViewById<RotaryPinLockView>(R.id.rotaryPinLock)
        rotaryPinLock.hapticsSoundEnabled = settingsStore.isRotaryHapticsSoundEnabled()
        val secretPinError = findViewById<TextView>(R.id.secretPinError)
        val btnSecretCancel = findViewById<Button>(R.id.btnSecretCancel)

        val appLabel = try {
            targetPackage?.let {
                packageManager.getApplicationLabel(packageManager.getApplicationInfo(it, 0)).toString()
            } ?: "This application"
        } catch (e: Exception) {
            "This application"
        }

        fakeErrorMessage.text = "Device storage is critically low. \"$appLabel\" failed to allocate runtime memory and was suspended to prevent system instability.\n\nPlease free up internal storage and try again."

        // If the user turned decoy mode off in Settings, skip the fake-error trick entirely
        // and go straight to the rotary PIN screen.
        if (!settingsStore.isDecoyModeEnabled()) {
            fakeErrorCard.visibility = View.GONE
            secretPinLayout.visibility = View.VISIBLE
            rotaryPinLock.reset()
        }

        val kickToHome = View.OnClickListener {
            goToHomeScreen()
        }
        btnFakeClose.setOnClickListener(kickToHome)
        btnFakeWait.setOnClickListener(kickToHome)

        // Secret Trigger
        var tapCount = 0
        var lastTapTime = 0L

        val triggerSecretPin: () -> Unit = {
            fakeErrorCard.visibility = View.GONE
            secretPinLayout.visibility = View.VISIBLE
            rotaryPinLock.reset()
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
            rotaryPinLock.reset()
            secretPinError.visibility = View.INVISIBLE
            secretPinLayout.visibility = View.GONE
            fakeErrorCard.visibility = View.VISIBLE
        }

        rotaryPinLock.onVerify = { pin ->
            if (pinManager.isLockedOut()) false else pinManager.check(pin) == PinManager.PinResult.REAL
        }
        rotaryPinLock.onSuccess = {
            secretPinError.visibility = View.INVISIBLE
            targetPackage?.let { LockAccessibilityService.unlockedPackages.add(it) }
            pinManager.clearAttempts()
            if (settingsStore.isAutoClearBreakInLogsEnabled()) pinManager.clearBreakInLog()
            handler.postDelayed({ finish() }, 350)
        }
        rotaryPinLock.onError = {
            if (pinManager.isLockedOut()) {
                secretPinError.text = "Too many attempts. Try again in ${pinManager.lockoutRemainingSeconds()}s"
            } else {
                pinManager.recordFailedAttempt()
                secretPinError.text = "Authorization failed"
            }
            secretPinError.visibility = View.VISIBLE
        }
    }

    @Suppress("DEPRECATION")
    private fun applyImmersiveFlags() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
    }

    private fun goToHomeScreen() {
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(homeIntent)
        finish()
    }

    @Suppress("OVERRIDE_DEPRECATION", "MissingSuperCall")
    override fun onBackPressed() {
        goToHomeScreen()
    }

    companion object {
        const val EXTRA_TARGET_PACKAGE = "target_package"
    }
}
