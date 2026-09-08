package com.anas.applocker

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var pinManager: PinManager
    private lateinit var pinInput: EditText
    private lateinit var pinConfirm: EditText
    private lateinit var subtitle: TextView
    private lateinit var error: TextView
    private lateinit var submit: Button
    private lateinit var credentialFormatGroup: RadioGroup
    private lateinit var formatLabel: TextView

    private var isFirstRunSetup = false
    private var selectedFormat = PinManager.CredentialFormat.NUMERIC

    private val lockoutHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val lockoutTicker = object : Runnable {
        override fun run() {
            updateLockoutUi()
            if (pinManager.isLockedOut()) lockoutHandler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        pinManager = PinManager(this)
        pinInput             = findViewById(R.id.pinInput)
        pinConfirm           = findViewById(R.id.pinInputConfirm)
        subtitle             = findViewById(R.id.subtitleText)
        error                = findViewById(R.id.errorText)
        submit               = findViewById(R.id.submitButton)
        credentialFormatGroup = findViewById(R.id.credentialFormatGroup)
        formatLabel          = findViewById(R.id.formatLabel)

        isFirstRunSetup = !pinManager.isSetupDone()

        if (isFirstRunSetup) {
            // ── First-time setup UI ──
            subtitle.text = "First time setup — choose your REAL vault credential"
            pinInput.hint = "Real credential"
            pinConfirm.hint = "Fake credential (opens Writify decoy)"
            pinConfirm.visibility = View.VISIBLE
            credentialFormatGroup.visibility = View.VISIBLE
            formatLabel.visibility = View.VISIBLE

            // Default: NUMERIC
            applyFormatToInputs(PinManager.CredentialFormat.NUMERIC)

            credentialFormatGroup.setOnCheckedChangeListener { _, checkedId ->
                selectedFormat = when (checkedId) {
                    R.id.formatAlphabetic -> PinManager.CredentialFormat.ALPHABETIC
                    else                  -> PinManager.CredentialFormat.NUMERIC
                }
                applyFormatToInputs(selectedFormat)
            }
        } else {
            // ── Normal login: use the real input type (not decoy — this is the vault owner's screen) ──
            pinInput.inputType = pinManager.getRealInputType()
            if (pinManager.isLockedOut()) lockoutTicker.run()
        }

        submit.setOnClickListener { onSubmit() }
    }

    override fun onDestroy() {
        super.onDestroy()
        lockoutHandler.removeCallbacks(lockoutTicker)
    }

    // ──────────────────────────────────────────────────────────────
    // Format helpers
    // ──────────────────────────────────────────────────────────────

    /**
     * Updates both credential fields to the correct InputType for the chosen format.
     * The login overlay uses the OPPOSITE (decoy) type; this screen uses the real type
     * so the vault owner can type their credential comfortably.
     */
    private fun applyFormatToInputs(format: PinManager.CredentialFormat) {
        val realInputType = when (format) {
            PinManager.CredentialFormat.ALPHABETIC ->
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            PinManager.CredentialFormat.NUMERIC ->
                InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        val currentReal    = pinInput.text
        val currentFake    = pinConfirm.text
        pinInput.inputType   = realInputType
        pinConfirm.inputType = realInputType
        // Restore text after inputType change (which clears the field on some versions)
        pinInput.text   = currentReal
        pinConfirm.text = currentFake

        pinInput.hint   = if (format == PinManager.CredentialFormat.ALPHABETIC) "Password" else "PIN"
        pinConfirm.hint = if (format == PinManager.CredentialFormat.ALPHABETIC) "Fake password" else "Fake PIN"
    }

    // ──────────────────────────────────────────────────────────────
    // Login / setup logic
    // ──────────────────────────────────────────────────────────────

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
            val minLen = if (selectedFormat == PinManager.CredentialFormat.NUMERIC) 4 else 6

            if (entered.length < minLen || fake.length < minLen) {
                error.text = "Both credentials need at least $minLen characters"
                return
            }
            if (entered == fake) {
                error.text = "Real and fake credentials must be different"
                return
            }
            pinManager.setupCredentials(entered, fake, selectedFormat)
            openDashboard()
            return
        }

        when (pinManager.check(entered)) {
            PinManager.PinResult.REAL -> {
                pinManager.clearAttempts()
                if (pinManager.shouldRemindPinChange()) showPinChangeReminder() else openDashboard()
            }
            PinManager.PinResult.FAKE -> {
                pinManager.clearAttempts()
                openWritify()
            }
            PinManager.PinResult.NONE -> {
                pinManager.recordFailedAttempt()
                if (pinManager.isLockedOut()) lockoutTicker.run()
                else error.text = "Incorrect credential"
            }
        }
        pinInput.text.clear()
    }

    private fun showPinChangeReminder() {
        AlertDialog.Builder(this)
            .setTitle("Credential change reminder")
            .setMessage("It's been a while since you last changed your vault credential. Consider updating it from the dashboard for better security.")
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
