package com.anas.applocker

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.MessageDigest

/**
 * Stores the REAL pin (unlocks the vault dashboard), the FAKE pin
 * (opens Writify normally, as a decoy), and an optional DURESS pin
 * (silently wipes the vault, then behaves like the fake pin so
 * nothing looks wrong). Also tracks failed attempts, lockout state,
 * a break-in log, and when the PIN was last changed.
 */
class PinManager(private val context: Context) {

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

    fun hasDuressPin(): Boolean = prefs.contains(KEY_DURESS)

    fun setupPins(realPin: String, fakePin: String, duressPin: String? = null) {
        val editor = prefs.edit()
            .putString(KEY_REAL, hash(realPin))
            .putString(KEY_FAKE, hash(fakePin))
            .putLong(KEY_PIN_CHANGED_AT, System.currentTimeMillis())
        if (!duressPin.isNullOrBlank()) {
            editor.putString(KEY_DURESS, hash(duressPin))
        }
        editor.apply()
        clearAttempts()
    }

    /** Returns which pin matched, or NONE if it matches none of them. */
    fun check(inputPin: String): PinResult {
        val hashed = hash(inputPin)
        return when (hashed) {
            prefs.getString(KEY_REAL, null) -> PinResult.REAL
            prefs.getString(KEY_FAKE, null) -> PinResult.FAKE
            prefs.getString(KEY_DURESS, null) -> PinResult.DURESS
            else -> PinResult.NONE
        }
    }

    // ---------- Failed attempts + lockout ----------

    fun recordFailedAttempt() {
        val count = prefs.getInt(KEY_FAIL_COUNT, 0) + 1
        val editor = prefs.edit().putInt(KEY_FAIL_COUNT, count)

        // Break-in log: keep the last 50 failed-attempt timestamps.
        val log = getBreakInLog().toMutableList()
        log.add(0, System.currentTimeMillis())
        while (log.size > 50) log.removeAt(log.size - 1)
        editor.putString(KEY_BREAKIN_LOG, log.joinToString(","))

        if (count >= MAX_ATTEMPTS) {
            editor.putLong(KEY_LOCKOUT_UNTIL, System.currentTimeMillis() + LOCKOUT_DURATION_MS)
            editor.putInt(KEY_FAIL_COUNT, 0)
        }
        editor.apply()
    }

    fun clearAttempts() {
        prefs.edit()
            .putInt(KEY_FAIL_COUNT, 0)
            .remove(KEY_LOCKOUT_UNTIL)
            .apply()
    }

    fun isLockedOut(): Boolean = lockoutRemainingSeconds() > 0

    fun lockoutRemainingSeconds(): Long {
        val until = prefs.getLong(KEY_LOCKOUT_UNTIL, 0)
        val remaining = (until - System.currentTimeMillis()) / 1000
        return if (remaining > 0) remaining else 0
    }

    /** Most recent failed-attempt timestamps, newest first. */
    fun getBreakInLog(): List<Long> {
        val raw = prefs.getString(KEY_BREAKIN_LOG, "") ?: ""
        if (raw.isBlank()) return emptyList()
        return raw.split(",").mapNotNull { it.toLongOrNull() }
    }

    fun clearBreakInLog() {
        prefs.edit().remove(KEY_BREAKIN_LOG).apply()
    }

    // ---------- PIN change reminder ----------

    /** Days since the PIN was last changed (large number if never set). */
    fun daysSincePinChange(): Long {
        val changedAt = prefs.getLong(KEY_PIN_CHANGED_AT, 0)
        if (changedAt == 0L) return Long.MAX_VALUE
        return (System.currentTimeMillis() - changedAt) / (1000 * 60 * 60 * 24)
    }

    fun shouldRemindPinChange(): Boolean = daysSincePinChange() >= PIN_REMINDER_DAYS

    fun dismissPinChangeReminderForNow() {
        // Push the "last changed" marker forward by a week so we don't nag daily.
        prefs.edit()
            .putLong(KEY_PIN_CHANGED_AT, System.currentTimeMillis() - (PIN_REMINDER_DAYS - 7) * 86400000L)
            .apply()
    }

    // ---------- Duress wipe ----------

    /** Deletes all vaulted files and unlocks every app. Called after a DURESS match. */
    fun performDuressWipe() {
        val vaultDir = java.io.File(context.filesDir, "vault")
        vaultDir.listFiles()?.forEach { it.delete() }

        val lockedAppsPrefs = context.getSharedPreferences("locked_apps", Context.MODE_PRIVATE)
        lockedAppsPrefs.edit().clear().apply()

        clearBreakInLog()
    }

    private fun hash(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(value.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    enum class PinResult { REAL, FAKE, DURESS, NONE }

    companion object {
        private const val KEY_REAL = "real_pin_hash"
        private const val KEY_FAKE = "fake_pin_hash"
        private const val KEY_DURESS = "duress_pin_hash"
        private const val KEY_FAIL_COUNT = "fail_count"
        private const val KEY_LOCKOUT_UNTIL = "lockout_until"
        private const val KEY_BREAKIN_LOG = "breakin_log"
        private const val KEY_PIN_CHANGED_AT = "pin_changed_at"

        const val MAX_ATTEMPTS = 5
        const val LOCKOUT_DURATION_MS = 30_000L // 30 seconds
        const val PIN_REMINDER_DAYS = 30L
    }
}
