package com.anas.applocker

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class LockOverlayActivity : AppCompatActivity() {

    private lateinit var pinManager: PinManager
    private var targetPackage: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_lock_overlay)

        pinManager = PinManager(this)
        targetPackage = intent.getStringExtra(EXTRA_TARGET_PACKAGE)

        val label = findViewById<TextView>(R.id.lockedAppLabel)
        val input = findViewById<EditText>(R.id.overlayPinInput)
        val error = findViewById<TextView>(R.id.overlayErrorText)
        val button = findViewById<Button>(R.id.overlayUnlockButton)

        val appLabel = try {
            targetPackage?.let {
                packageManager.getApplicationLabel(packageManager.getApplicationInfo(it, 0))
            }
        } catch (e: Exception) { null }
        label.text = "\"${appLabel ?: targetPackage}\" is locked"

        button.setOnClickListener {
            val entered = input.text.toString()
            // Only the REAL pin unlocks a locked app. The fake pin does
            // nothing here — it only has meaning on the main entry screen.
            if (pinManager.check(entered) == PinManager.PinResult.REAL) {
                targetPackage?.let { LockAccessibilityService.unlockedPackages.add(it) }
                finish()
            } else {
                error.text = "Incorrect PIN"
                input.text.clear()
            }
        }
    }

    /** Block the hardware back button from dismissing the lock screen. */
    override fun onBackPressed() {
        moveTaskToBack(true)
    }

    companion object {
        const val EXTRA_TARGET_PACKAGE = "target_package"
    }
}
