package com.anas.applocker

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var pinManager: PinManager
    private lateinit var settingsStore: SettingsStore
    private lateinit var pinInput: EditText
    private lateinit var pinConfirm: EditText
    private lateinit var subtitle: TextView
    private lateinit var error: TextView
    private lateinit var submit: Button

    /** True while the user is doing first-time setup of the PINs. */
    private var isFirstRunSetup = false

    /** Ensures the auto biometric prompt only launches once per activity instance -
     *  if the user cancels it, the PIN screen underneath is the fallback for the rest
     *  of this visit, rather than the prompt re-appearing on every resume. */
    private var biometricAutoAttempted = false

    private val lockoutHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val lockoutTicker = object : Runnable {
        override fun run() {
            updateLockoutUi()
            if (pinManager.isLockedOut()) {
                lockoutHandler.postDelayed(this, 1000)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsStore = SettingsStore(this)
        if (settingsStore.isStealthRecentsEnabled()) {
            window.setFlags(
                android.view.WindowManager.LayoutParams.FLAG_SECURE,
                android.view.WindowManager.LayoutParams.FLAG_SECURE
            )
        }
        setContentView(R.layout.activity_main)

        pinManager = PinManager(this)
        pinInput = findViewById(R.id.pinInput)
        pinConfirm = findViewById(R.id.pinInputConfirm)
        subtitle = findViewById(R.id.subtitleText)
        error = findViewById(R.id.errorText)
        submit = findViewById(R.id.submitButton)

        isFirstRunSetup = !pinManager.isSetupDone()

        if (isFirstRunSetup) {
            subtitle.text = "First time setup — choose your REAL vault PIN"
            pinInput.hint = "Real PIN"
            pinConfirm.hint = "Fake PIN (opens Writify)"
            pinConfirm.visibility = android.view.View.VISIBLE
        }

        submit.setOnClickListener { onSubmit() }

        if (!isFirstRunSetup && pinManager.isLockedOut()) {
            lockoutTicker.run()
        }

        // Auto-detect fingerprint the instant the vault entry screen opens - if biometrics
        // are enabled and enrolled, the user shouldn't have to tap anything before placing
        // their finger on the sensor. Skipped entirely during first-run setup and while
        // locked out from too many failed PIN attempts.
        if (!isFirstRunSetup && !pinManager.isLockedOut()) {
            attemptAutoBiometricUnlock()
        }
    }

    private fun attemptAutoBiometricUnlock() {
        if (biometricAutoAttempted) return
        if (!settingsStore.isBiometricFallbackEnabled()) return
        val authenticators = BiometricUnlockActivity.resolveAvailableAuthenticators(this) ?: return
        biometricAutoAttempted = true

        try {
            val executor = ContextCompat.getMainExecutor(this)
            val prompt = BiometricPrompt(this, executor, object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    pinManager.clearAttempts()
                    openDashboard()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    // Cancelled or failed too many times - the PIN screen is already
                    // visible underneath, so there's nothing else to do here.
                }

                override fun onAuthenticationFailed() {
                    // Keep the prompt open for another attempt.
                }
            })

            val promptInfo = BiometricPrompt.PromptInfo.Builder()
                .setTitle("Unlock Writify")
                .setSubtitle("Confirm your identity to open your vault")
                .setAllowedAuthenticators(authenticators)
                .setNegativeButtonText("Use PIN instead")
                .build()

            prompt.authenticate(promptInfo)
        } catch (e: Exception) {
            // Broken/partial biometric HAL on some OEM builds - fall back to PIN silently.
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        lockoutHandler.removeCallbacks(lockoutTicker)
    }

    private fun updateLockoutUi() {
        val remaining = pinManager.lockoutRemainingSeconds()
        if (remaining > 0) {
            submit.isEnabled = false
            error.text = "Too many attempts. Try again in ${remaining}s"
        } else {
            submit.isEnabled = true
            error.text = ""
        }
    }

    private fun onSubmit() {
        if (!isFirstRunSetup && pinManager.isLockedOut()) return

        error.text = ""
        val entered = pinInput.text.toString()

        if (isFirstRunSetup) {
            val fake = pinConfirm.text.toString()

            if (entered.length < 4 || fake.length < 4) {
                error.text = "Real and fake PIN need at least 4 digits"
                return
            }
            if (entered == fake) {
                error.text = "Real and fake PIN must be different"
                return
            }
            pinManager.setupPins(realPin = entered, fakePin = fake)
            openDashboard()
            return
        }

        when (pinManager.check(entered)) {
            PinManager.PinResult.REAL -> {
                pinManager.clearAttempts()
                if (pinManager.shouldRemindPinChange()) {
                    showPinChangeReminder()
                } else {
                    openDashboard()
                }
            }
            PinManager.PinResult.FAKE -> {
                pinManager.clearAttempts()
                openWritify()
            }
            PinManager.PinResult.NONE -> {
                pinManager.recordFailedAttempt()
                if (pinManager.isLockedOut()) {
                    lockoutTicker.run()
                } else {
                    error.text = "Incorrect PIN"
                }
            }
        }
        pinInput.text.clear()
    }

    private fun showPinChangeReminder() {
        AlertDialog.Builder(this)
            .setTitle("PIN change reminder")
            .setMessage("It's been a while since you last changed your PIN. Consider updating it from the dashboard for better security.")
            .setPositiveButton("Continue") { _, _ ->
                pinManager.dismissPinChangeReminderForNow()
                openDashboard()
            }
            .setCancelable(false)
            .show()
    }

    private fun openDashboard() {
        startActivity(Intent(this, DashboardActivity::class.java))
        finish()
    }

    private fun openWritify() {
        startActivity(Intent(this, WritifyActivity::class.java))
        finish()
    }
}
