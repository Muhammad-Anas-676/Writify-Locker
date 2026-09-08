package com.anas.applocker

import android.content.Context
import android.content.SharedPreferences
import android.text.InputType
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.MessageDigest

/**
 * Stores the REAL credential (unlocks the vault dashboard) and the FAKE credential
 * (opens Writify normally, as a decoy). Also tracks:
 *  - [CredentialFormat] — ALPHABETIC password or NUMERIC PIN.
 *  - Failed attempts and lockout state.
 *  - Break-in log (last 50 failed attempt timestamps).
 *  - When the credential was last changed (for the change-reminder feature).
 *
 * Credential format drives the INVERTED DECOY mechanism:
 *  - Real = ALPHABETIC  →  overlays show a NUMERIC keypad (wrong keyboard).
 *  - Real = NUMERIC     →  overlays show a QWERTY keyboard (wrong keyboard).
 * After the user triggers the secret gesture, the keyboard flips to the real type.
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

    // ──────────────────────────────────────────────────────────────
    // Credential format
    // ──────────────────────────────────────────────────────────────

    enum class CredentialFormat { NUMERIC, ALPHABETIC }

    fun getCredentialFormat(): CredentialFormat {
        val raw = prefs.getString(KEY_CREDENTIAL_FORMAT, null) ?: return CredentialFormat.NUMERIC
        return runCatching { CredentialFormat.valueOf(raw) }.getOrDefault(CredentialFormat.NUMERIC)
    }

    /**
     * DECOY InputType — shown to an observer or attacker. Always the OPPOSITE of the real format.
     *   Real = ALPHABETIC  →  decoy = numeric keypad  (TYPE_CLASS_NUMBER | NUMBER_VARIATION_PASSWORD)
     *   Real = NUMERIC     →  decoy = QWERTY keyboard (TYPE_CLASS_TEXT  | TEXT_VARIATION_PASSWORD)
     */
    fun getDecoyInputType(): Int = when (getCredentialFormat()) {
        CredentialFormat.ALPHABETIC ->
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        CredentialFormat.NUMERIC ->
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
    }

    /**
     * REAL InputType — exposed only after the secret gesture flips the keyboard.
     *   Real = ALPHABETIC  →  QWERTY password keyboard
     *   Real = NUMERIC     →  numeric PIN keyboard
     */
    fun getRealInputType(): Int = when (getCredentialFormat()) {
        CredentialFormat.ALPHABETIC ->
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        CredentialFormat.NUMERIC ->
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
    }

    // ──────────────────────────────────────────────────────────────
    // Setup & verification
    // ──────────────────────────────────────────────────────────────

    fun isSetupDone(): Boolean = prefs.contains(KEY_REAL) && prefs.contains(KEY_FAKE)

    /** Primary setup — stores both credentials and the chosen format. */
    fun setupCredentials(
        realCredential: String,
        fakeCredential: String,
        format: CredentialFormat
    ) {
        prefs.edit()
            .putString(KEY_REAL, hash(realCredential))
            .putString(KEY_FAKE, hash(fakeCredential))
            .putString(KEY_CREDENTIAL_FORMAT, format.name)
            .putLong(KEY_PIN_CHANGED_AT, System.currentTimeMillis())
            .apply()
        clearAttempts()
    }

    /** Backward-compatible alias — assumes NUMERIC format. */
    fun setupPins(realPin: String, fakePin: String) =
        setupCredentials(realPin, fakePin, CredentialFormat.NUMERIC)

    /** Returns which credential matched, or NONE if neither matched. */
    fun check(inputCredential: String): PinResult {
        val hashed = hash(inputCredential)
        return when (hashed) {
            prefs.getString(KEY_REAL, null) -> PinResult.REAL
            prefs.getString(KEY_FAKE, null) -> PinResult.FAKE
            else                             -> PinResult.NONE
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Failed attempts + lockout
    // ──────────────────────────────────────────────────────────────

    fun recordFailedAttempt() {
        val count = prefs.getInt(KEY_FAIL_COUNT, 0) + 1
        val editor = prefs.edit().putInt(KEY_FAIL_COUNT, count)

        // Break-in log: newest-first, capped at 50 entries.
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

    // ──────────────────────────────────────────────────────────────
    // Credential-change reminder
    // ──────────────────────────────────────────────────────────────

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

    // ──────────────────────────────────────────────────────────────
    // Internal
    // ──────────────────────────────────────────────────────────────

    private fun hash(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes  = digest.digest(value.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    enum class PinResult { REAL, FAKE, NONE }

    companion object {
        private const val KEY_REAL              = "real_pin_hash"
        private const val KEY_FAKE              = "fake_pin_hash"
        private const val KEY_CREDENTIAL_FORMAT = "credential_format"
        private const val KEY_FAIL_COUNT        = "fail_count"
        private const val KEY_LOCKOUT_UNTIL     = "lockout_until"
        private const val KEY_BREAKIN_LOG       = "breakin_log"
        private const val KEY_PIN_CHANGED_AT    = "pin_changed_at"

        const val MAX_ATTEMPTS        = 5
        const val LOCKOUT_DURATION_MS = 30_000L // 30 seconds
        const val PIN_REMINDER_DAYS   = 30L
    }
}
