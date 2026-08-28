package com.anas.applocker

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat

/**
 * Fully transparent, no-UI-of-its-own activity: it exists purely to host a BiometricPrompt,
 * since BiometricPrompt requires a FragmentActivity/host and the lock overlay itself is
 * drawn by an AccessibilityService (which cannot host one directly).
 *
 * Launched from [LockAccessibilityService] when the user taps "Use biometrics" on the
 * secret PIN screen and SettingsStore.isBiometricFallbackEnabled() is true. On success it
 * reports back via the static [LockAccessibilityService.notifyBiometricUnlock] callback and
 * finishes immediately so the underlying overlay/rotary PIN screen is what the user sees
 * again a frame later (this activity is never visually present for longer than the OS
 * biometric sheet itself).
 */
class BiometricUnlockActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val biometricManager = BiometricManager.from(this)
        val canAuth = biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK)
        if (canAuth != BiometricManager.BIOMETRIC_SUCCESS) {
            finish()
            return
        }

        val executor = ContextCompat.getMainExecutor(this)
        val prompt = BiometricPrompt(this, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                LockAccessibilityService.notifyBiometricUnlockResult(success = true)
                finish()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                LockAccessibilityService.notifyBiometricUnlockResult(success = false)
                finish()
            }

            override fun onAuthenticationFailed() {
                // Keep the prompt open for another attempt - only errors/success finish it.
            }
        })

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock")
            .setSubtitle("Confirm your identity to continue")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
            .setNegativeButtonText("Use PIN instead")
            .build()

        prompt.authenticate(promptInfo)
    }
}
