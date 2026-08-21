package com.anas.applocker

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var pinManager: PinManager
    private lateinit var pinInput: EditText
    private lateinit var pinConfirm: EditText
    private lateinit var subtitle: TextView
    private lateinit var error: TextView
    private lateinit var submit: Button

    /** True while the user is doing first-time setup of both PINs. */
    private var isFirstRunSetup = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
    }

    private fun onSubmit() {
        error.text = ""
        val entered = pinInput.text.toString()

        if (isFirstRunSetup) {
            val fake = pinConfirm.text.toString()
            if (entered.length < 4 || fake.length < 4) {
                error.text = "Both PINs need at least 4 digits"
                return
            }
            if (entered == fake) {
                error.text = "Real and fake PIN must be different"
                return
            }
            pinManager.setupPins(realPin = entered, fakePin = fake)
            // After setup, treat this launch as unlocking with the real pin
            openDashboard()
            return
        }

        when (pinManager.check(entered)) {
            PinManager.PinResult.REAL -> openDashboard()
            PinManager.PinResult.FAKE -> openWritify()
            PinManager.PinResult.NONE -> error.text = "Incorrect PIN"
        }
        pinInput.text.clear()
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
