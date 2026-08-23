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

class LockOverlayActivity : AppCompatActivity() {

    private lateinit var pinManager: PinManager
    private var targetPackage: String? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

        val secretPinInput = findViewById<EditText>(R.id.secretPinInput)
        val secretPinError = findViewById<TextView>(R.id.secretPinError)
        val btnSecretCancel = findViewById<Button>(R.id.btnSecretCancel)
        val btnSecretUnlock = findViewById<Button>(R.id.btnSecretUnlock)

        val appLabel = try {
            targetPackage?.let {
                packageManager.getApplicationLabel(packageManager.getApplicationInfo(it, 0)).toString()
            } ?: "This application"
        } catch (e: Exception) {
            "This application"
        }

        fakeErrorMessage.text = "Device storage is critically low. \"$appLabel\" failed to allocate runtime memory and was suspended to prevent system instability.\n\nPlease free up internal storage and try again."

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
                targetPackage?.let { LockAccessibilityService.unlockedPackages.add(it) }
                finish()
            } else {
                secretPinError.text = "Authorization failed"
                secretPinError.visibility = View.VISIBLE
                secretPinInput.text.clear()
            }
        }
    }

    private fun goToHomeScreen() {
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(homeIntent)
        finish()
    }

    override fun onBackPressed() {
        goToHomeScreen()
    }

    companion object {
        const val EXTRA_TARGET_PACKAGE = "target_package"
    }
}