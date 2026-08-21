package com.anas.applocker

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.MessageDigest

/**
 * Stores the REAL pin (unlocks the vault dashboard) and the FAKE pin
 * (opens Writify normally, as a decoy) using encrypted storage so
 * neither value sits in plaintext on disk.
 */
class PinManager(context: Context) {

    private val prefs: SharedPreferences

    init {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        prefs = EncryptedSharedPreferences.create(
            context,
            "vault_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun isSetupDone(): Boolean = prefs.contains(KEY_REAL) && prefs.contains(KEY_FAKE)

    fun setupPins(realPin: String, fakePin: String) {
        prefs.edit()
            .putString(KEY_REAL, hash(realPin))
            .putString(KEY_FAKE, hash(fakePin))
            .apply()
    }

    /** Returns which pin matched, or NONE if it matches neither. */
    fun check(inputPin: String): PinResult {
        val hashed = hash(inputPin)
        return when (hashed) {
            prefs.getString(KEY_REAL, null) -> PinResult.REAL
            prefs.getString(KEY_FAKE, null) -> PinResult.FAKE
            else -> PinResult.NONE
        }
    }

    private fun hash(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(value.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    enum class PinResult { REAL, FAKE, NONE }

    companion object {
        private const val KEY_REAL = "real_pin_hash"
        private const val KEY_FAKE = "fake_pin_hash"
    }
}
