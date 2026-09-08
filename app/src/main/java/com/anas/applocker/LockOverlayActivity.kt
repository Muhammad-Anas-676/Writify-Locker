package com.anas.applocker

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Fallback lock-screen overlay Activity — used in edge cases where the
 * [LockAccessibilityService] overlay cannot be attached via WindowManager
 * (e.g., very early in service startup).
 *
 * Implements the same INVERTED DECOY authentication mechanism as the service:
 *  • The credential EditText starts with the DECOY InputType (wrong keyboard).
 *  • A 3-tap rapid sequence OR long-press on the warning icon/title reveals
 *    the secret credential panel (still showing the decoy keyboard).
 *  • Inside the secret panel, the SAME gesture triggers [triggerKeyboardFlip],
 *    which calls [InputMethodManager.restartInput] to switch to the REAL keyboard.
 *  • Successful auth dismisses the overlay; failed auth shows an error + resets.
 *  • Dismissing without auth sends the user back to Home.
 */
class LockOverlayActivity : AppCompatActivity() {

    private lateinit var pinManager: PinManager
    private var targetPackage: String? = null
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var fakeErrorCard: LinearLayout
    private lateinit var secretPinLayout: LinearLayout
    private lateinit var warningIcon: ImageView
    private lateinit var warningTitle: TextView
    private lateinit var fakeErrorMessage: TextView
    private lateinit var secretPinInput: EditText
    private lateinit var secretPinError: TextView
    private lateinit var btnFakeClose: Button
    private lateinit var btnFakeWait: Button
    private lateinit var btnSecretCancel: Button
    private lateinit var btnSecretUnlock: Button

    private var isKeyboardFlippedToReal = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_lock_overlay)

        pinManager    = PinManager(this)
        targetPackage = intent.getStringExtra(EXTRA_TARGET_PACKAGE)

        fakeErrorCard    = findViewById(R.id.fakeErrorCard)
        secretPinLayout  = findViewById(R.id.secretPinLayout)
        warningIcon      = findViewById(R.id.warningIcon)
        warningTitle     = findViewById(R.id.warningTitle)
        fakeErrorMessage = findViewById(R.id.fakeErrorMessage)
        secretPinInput   = findViewById(R.id.secretPinInput)
        secretPinError   = findViewById(R.id.secretPinError)
        btnFakeClose     = findViewById(R.id.btnFakeClose)
        btnFakeWait      = findViewById(R.id.btnFakeWait)
        btnSecretCancel  = findViewById(R.id.btnSecretCancel)
        btnSecretUnlock  = findViewById(R.id.btnSecretUnlock)

        // Personalise the fake storage-error message with the locked app's label
        val appLabel = try {
            targetPackage?.let {
                packageManager.getApplicationLabel(
                    packageManager.getApplicationInfo(it, 0)
                ).toString()
            } ?: "This application"
        } catch (_: Exception) { "This application" }

        fakeErrorMessage.text =
            "Device storage is critically low. \"$appLabel\" failed to allocate runtime memory " +
            "and was suspended to prevent system instability.\n\nPlease free up internal storage and try again."

        // Dismiss without auth → Home
        val kickToHome = View.OnClickListener { goToHomeScreen() }
        btnFakeClose.setOnClickListener(kickToHome)
        btnFakeWait.setOnClickListener(kickToHome)

        wireDecoyTrigger()

        btnSecretCancel.setOnClickListener { resetToDecoyCard() }
        btnSecretUnlock.setOnClickListener { verifyCredential() }
    }

    // ──────────────────────────────────────────────────────────────
    // Decoy trigger wiring
    // ──────────────────────────────────────────────────────────────

    /**
     * Wires the 3-tap / long-press secret trigger onto the warning icon and title.
     * On the decoy card, the trigger opens the secret panel (with DECOY keyboard).
     * Inside the panel, the SAME gesture calls [triggerKeyboardFlip].
     */
    private fun wireDecoyTrigger() {
        var tapCount  = 0
        var lastTapMs = 0L

        val openSecretPanel = View.OnClickListener {
            val now = System.currentTimeMillis()
            if (now - lastTapMs < 500) { tapCount++; if (tapCount >= 3) { showSecretPanel(); tapCount = 0 } }
            else tapCount = 1
            lastTapMs = now
        }

        warningIcon.setOnClickListener(openSecretPanel)
        warningTitle.setOnClickListener(openSecretPanel)
        warningIcon.setOnLongClickListener  { showSecretPanel(); true }
        warningTitle.setOnLongClickListener { showSecretPanel(); true }
    }

    private fun wireFlipTrigger() {
        var tapCount  = 0
        var lastTapMs = 0L

        val flip = View.OnClickListener {
            val now = System.currentTimeMillis()
            if (now - lastTapMs < 500) { tapCount++; if (tapCount >= 3) { triggerKeyboardFlip(); tapCount = 0 } }
            else tapCount = 1
            lastTapMs = now
        }

        warningIcon.setOnClickListener(flip)
        warningTitle.setOnClickListener(flip)
        warningIcon.setOnLongClickListener  { triggerKeyboardFlip(); true }
        warningTitle.setOnLongClickListener { triggerKeyboardFlip(); true }
    }

    // ──────────────────────────────────────────────────────────────
    // Panel transitions
    // ──────────────────────────────────────────────────────────────

    /**
     * Reveals the credential input panel. Initialises the EditText with the DECOY InputType
     * so the wrong keyboard is shown. The gesture trigger inside the panel flips it to the
     * REAL type via [triggerKeyboardFlip].
     */
    private fun showSecretPanel() {
        isKeyboardFlippedToReal = false

        // Start with DECOY keyboard — inverted from the user's real credential format
        secretPinInput.inputType = pinManager.getDecoyInputType()

        fakeErrorCard.visibility   = View.GONE
        secretPinLayout.visibility = View.VISIBLE
        secretPinInput.requestFocus()

        // Inside the panel, the same gesture now flips to the real keyboard
        wireFlipTrigger()

        handler.postDelayed({
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(secretPinInput, InputMethodManager.SHOW_IMPLICIT)
        }, 100)
    }

    /**
     * Flips the credential EditText to the REAL InputType and calls
     * [InputMethodManager.restartInput] so the keyboard redraws immediately.
     */
    private fun triggerKeyboardFlip() {
        if (isKeyboardFlippedToReal) return
        isKeyboardFlippedToReal = true

        secretPinInput.inputType = pinManager.getRealInputType()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.restartInput(secretPinInput)
        imm?.showSoftInput(secretPinInput, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun resetToDecoyCard() {
        secretPinInput.text.clear()
        secretPinError.visibility   = View.INVISIBLE
        secretPinLayout.visibility  = View.GONE
        fakeErrorCard.visibility    = View.VISIBLE
        isKeyboardFlippedToReal = false
        wireDecoyTrigger() // restore decoy-card trigger listeners
    }

    // ──────────────────────────────────────────────────────────────
    // Auth verification
    // ──────────────────────────────────────────────────────────────

    private fun verifyCredential() {
        val entered = secretPinInput.text.toString()
        if (pinManager.check(entered) == PinManager.PinResult.REAL) {
            targetPackage?.let { LockAccessibilityService.unlockedPackages.add(it) }
            finish()
        } else {
            pinManager.recordFailedAttempt()
            secretPinError.text = "Authorization failed"
            secretPinError.visibility = View.VISIBLE
            secretPinInput.text.clear()
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Navigation
    // ──────────────────────────────────────────────────────────────

    private fun goToHomeScreen() {
        startActivity(Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        })
        finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        goToHomeScreen()
    }

    companion object {
        const val EXTRA_TARGET_PACKAGE = "target_package"
    }
}
