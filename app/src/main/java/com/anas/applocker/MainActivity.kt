package com.anas.applocker

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var pinManager: PinManager
    private lateinit var pinInput: EditText
    private lateinit var pinConfirm: EditText
    private lateinit var pinDuress: EditText
    private lateinit var skipDuressText: TextView
    private lateinit var subtitle: TextView
    private lateinit var error: TextView
    private lateinit var submit: Button

    /** True while the user is doing first-time setup of the PINs. */
    private var isFirstRunSetup = false
    private var duressSkipped = false

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
        setContentView(R.layout.activity_main)

        pinManager = PinManager(this)
        pinInput = findViewById(R.id.pinInput)
        pinConfirm = findViewById(R.id.pinInputConfirm)
        pinDuress = findViewById(R.id.pinInputDuress)
        skipDuressText = findViewById(R.id.skipDuressText)
        subtitle = findViewById(R.id.subtitleText)
        error = findViewById(R.id.errorText)
        submit = findViewById(R.id.submitButton)

        isFirstRunSetup = !pinManager.isSetupDone()

        if (isFirstRunSetup) {
            subtitle.text = "First time setup — choose your REAL vault PIN"
            pinInput.hint = "Real PIN"
            pinConfirm.hint = "Fake PIN (opens Writify)"
            pinConfirm.visibility = android.view.View.VISIBLE
            pinDuress.visibility = android.view.View.VISIBLE
            skipDuressText.visibility = android.view.View.VISIBLE
            skipDuressText.setOnClickListener {
                duressSkipped = true
                pinDuress.text.clear()
                pinDuress.isEnabled = false
                pinDuress.hint = "Duress PIN skipped"
            }
        }

        submit.setOnClickListener { onSubmit() }

        if (!isFirstRunSetup && pinManager.isLockedOut()) {
            lockoutTicker.run()
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
            val duress = if (duressSkipped) null else pinDuress.text.toString().ifBlank { null }

            if (entered.length < 4 || fake.length < 4) {
                error.text = "Real and fake PIN need at least 4 digits"
                return
            }
            if (entered == fake) {
                error.text = "Real and fake PIN must be different"
                return
            }
            if (duress != null) {
                if (duress.length < 4) {
                    error.text = "Duress PIN needs at least 4 digits (or tap Skip)"
                    return
                }
                if (duress == entered || duress == fake) {
                    error.text = "Duress PIN must be different from the others"
                    return
                }
            }
            pinManager.setupPins(realPin = entered, fakePin = fake, duressPin = duress)
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
            PinManager.PinResult.DURESS -> {
                // Wipe silently, then behave exactly like the fake pin —
                // nothing on screen should hint anything happened.
                pinManager.performDuressWipe()
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
