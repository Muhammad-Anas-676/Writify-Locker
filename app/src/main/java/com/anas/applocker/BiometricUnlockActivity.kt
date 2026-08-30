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

        try {
            val authenticators = resolveAvailableAuthenticators(this)
            if (authenticators == null) {
                // No usable biometric on this device/state (not enrolled, no hardware, etc.) -
                // finish immediately so the rotary PIN screen underneath stays the fallback.
                LockAccessibilityService.notifyBiometricUnlockResult(success = false)
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
                .setAllowedAuthenticators(authenticators)
                .setNegativeButtonText("Use PIN instead")
                .build()

            prompt.authenticate(promptInfo)
        } catch (e: Exception) {
            // Some OEM Android 9-11 builds throw from a broken/partial biometric HAL rather
            // than reporting an honest canAuthenticate() failure - treat it the same way as
            // "biometrics unavailable" and fall back to the PIN screen instead of crashing.
            LockAccessibilityService.notifyBiometricUnlockResult(success = false)
            finish()
        }
    }

    companion object {
        /**
         * Returns the best authenticator flag combo this device can actually use right now,
         * or null if none work. Tries BIOMETRIC_WEAK or BIOMETRIC_STRONG combined first
         * (covers Android 9-14 devices whose sensor is only registered under one or the
         * other), then falls back to WEAK alone and STRONG alone for older/narrower API
         * behavior on Android 8.0-8.1 (API 26/27) and early API 28 devices.
         */
        fun resolveAvailableAuthenticators(context: android.content.Context): Int? {
            val biometricManager = BiometricManager.from(context)
            val combos = intArrayOf(
                BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.BIOMETRIC_STRONG,
                BiometricManager.Authenticators.BIOMETRIC_WEAK,
                BiometricManager.Authenticators.BIOMETRIC_STRONG
            )
            for (combo in combos) {
                if (biometricManager.canAuthenticate(combo) == BiometricManager.BIOMETRIC_SUCCESS) {
                    return combo
                }
            }
            return null
        }
    }
}
