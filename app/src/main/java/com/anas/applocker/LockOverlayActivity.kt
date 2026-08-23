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

    private val timerHandler = Handler(Looper.getMainLooper())
    private var secondsLeft = 15
    private var timerRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_lock_overlay)

        pinManager = PinManager(this)
        targetPackage = intent.getStringExtra(EXTRA_TARGET_PACKAGE)

        val secretInput = findViewById<EditText>(R.id.secretPinInput)
        val timerText = findViewById<TextView>(R.id.countdownTimerText)
        val closeButton = findViewById<Button>(R.id.btnDecoyClose)

        closeButton.setOnClickListener {
            closeAndGoHome()
        }

        val showKeyboardListener = View.OnClickListener {
            secretInput.requestFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(secretInput, InputMethodManager.SHOW_IMPLICIT)
        }
        findViewById<View>(android.R.id.content).setOnClickListener(showKeyboardListener)
        findViewById<View>(R.id.cardDecoy).setOnClickListener(showKeyboardListener)

        secretInput.addTextChangedListener(object : TextWatcher {
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

        secretInput.requestFocus()
        start15SecondsTimer(timerText)
    }

    private fun start15SecondsTimer(timerView: TextView) {
        stopTimer()
        secondsLeft = 15

        timerRunnable = object : Runnable {
            override fun run() {
                secondsLeft--
                timerView.text = "Closing in ${secondsLeft}s..."

                if (secondsLeft <= 0) {
                    closeAndGoHome()
                } else {
                    timerHandler.postDelayed(this, 1000)
                }
            }
        }
        timerHandler.postDelayed(timerRunnable!!, 1000)
    }

    private fun stopTimer() {
        timerRunnable?.let { timerHandler.removeCallbacks(it) }
        timerRunnable = null
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