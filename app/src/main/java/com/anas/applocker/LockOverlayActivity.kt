package com.anas.applocker

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class LockOverlayActivity : AppCompatActivity() {

    private lateinit var pinManager: PinManager
    private var targetPackage: String? = null

    private val timeoutHandler = Handler(Looper.getMainLooper())
    private var remainingSeconds = 15
    private var countdownRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_lock_overlay)

        pinManager = PinManager(this)
        targetPackage = intent.getStringExtra(EXTRA_TARGET_PACKAGE)

        val hiddenInput = findViewById<EditText>(R.id.hiddenPinInput)
        val timerText = findViewById<TextView>(R.id.timerDecoyText)
        val btnClose = findViewById<Button>(R.id.btnDecoyClose)

        btnClose.setOnClickListener {
            closeAndGoHome()
        }

        findViewById<View>(android.R.id.content).setOnClickListener {
            focusSecretKeyboard(hiddenInput)
        }
        findViewById<View>(R.id.cardDecoy).setOnClickListener {
            focusSecretKeyboard(hiddenInput)
        }

        hiddenInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable?) {
                val entered = s?.toString() ?: return
                if (entered.length >= 4) {
                    if (pinManager.check(entered) == PinManager.PinResult.REAL) {
                        targetPackage?.let { LockAccessibilityService.unlockedPackages.add(it) }
                        stopTimer()
                        finish()
                    }
                }
            }
        })

        focusSecretKeyboard(hiddenInput)
        start15SecondsTimeout(timerText)
    }

    private fun focusSecretKeyboard(hiddenInput: EditText) {
        hiddenInput.requestFocus()
        hiddenInput.postDelayed({
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(hiddenInput, InputMethodManager.SHOW_IMPLICIT)
        }, 200)
    }

    private fun start15SecondsTimeout(timerTextView: TextView) {
        stopTimer()
        remainingSeconds = 15

        countdownRunnable = object : Runnable {
            override fun run() {
                remainingSeconds--
                timerTextView.text = "Closing in ${remainingSeconds}s..."

                if (remainingSeconds <= 0) {
                    closeAndGoHome()
                } else {
                    timeoutHandler.postDelayed(this, 1000)
                }
            }
        }
        timeoutHandler.postDelayed(countdownRunnable!!, 1000)
    }

    private fun stopTimer() {
        countdownRunnable?.let { timeoutHandler.removeCallbacks(it) }
        countdownRunnable = null
    }

    private fun closeAndGoHome() {
        stopTimer()
        val startMain = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(startMain)
        finish()
    }

    override fun onBackPressed() {
        closeAndGoHome()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopTimer()
    }

    companion object {
        const val EXTRA_TARGET_PACKAGE = "target_package"
    }
}