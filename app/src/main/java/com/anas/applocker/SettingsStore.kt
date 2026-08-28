package com.anas.applocker

import android.content.Context

/**
 * Every user-facing preference from the Settings screen lives here, in one plain
 * (non-encrypted) SharedPreferences file. Nothing sensitive is stored in it - no PINs,
 * no vault contents - just toggles and small numbers, so it's safe to include in the
 * Backup & Restore JSON as-is.
 */
class SettingsStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("app_settings", Context.MODE_PRIVATE)

    // ---------- 1. Decoy / stealth mode ----------

    fun isDecoyModeEnabled(): Boolean = prefs.getBoolean(KEY_DECOY_MODE, true)

    fun setDecoyModeEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DECOY_MODE, enabled).apply()
    }

    // ---------- 2. Instant relock on app switch ----------
    // When ON (default): leaving a just-unlocked app re-locks it the moment it goes to
    // background, regardless of the auto-relock timer below. When OFF: the app stays
    // unlocked until the Auto Re-lock Timer duration has elapsed in the background.

    fun isInstantRelockOnSwitch(): Boolean = prefs.getBoolean(KEY_INSTANT_RELOCK, true)

    fun setInstantRelockOnSwitch(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_INSTANT_RELOCK, enabled).apply()
    }

    // ---------- 3. Fake storage notification alert ----------

    fun isFakeStorageNotificationEnabled(): Boolean = prefs.getBoolean(KEY_FAKE_STORAGE_NOTIF, false)

    fun setFakeStorageNotificationEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_FAKE_STORAGE_NOTIF, enabled).apply()
    }

    // ---------- 4. Stealth recents protection (FLAG_SECURE) ----------

    fun isStealthRecentsEnabled(): Boolean = prefs.getBoolean(KEY_STEALTH_RECENTS, true)

    fun setStealthRecentsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_STEALTH_RECENTS, enabled).apply()
    }

    // ---------- 5. Rotary dial haptics & sound ----------

    fun isRotaryHapticsSoundEnabled(): Boolean = prefs.getBoolean(KEY_ROTARY_HAPTICS, true)

    fun setRotaryHapticsSoundEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ROTARY_HAPTICS, enabled).apply()
    }

    // ---------- 6. Auto re-lock ----------

    fun getAutoRelockSeconds(): Long = prefs.getLong(KEY_AUTO_RELOCK_SECONDS, 15L)

    fun setAutoRelockSeconds(seconds: Long) {
        prefs.edit().putLong(KEY_AUTO_RELOCK_SECONDS, seconds).apply()
    }

    // ---------- 7. Shake to lock ----------

    fun isShakeToLockEnabled(): Boolean = prefs.getBoolean(KEY_SHAKE_TO_LOCK, false)

    fun setShakeToLockEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SHAKE_TO_LOCK, enabled).apply()
    }

    // ---------- 8. Flip to exit ----------

    fun isFlipToExitEnabled(): Boolean = prefs.getBoolean(KEY_FLIP_TO_EXIT, false)

    fun setFlipToExitEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_FLIP_TO_EXIT, enabled).apply()
    }

    // ---------- 9. Auto-clear break-in logs ----------

    fun isAutoClearBreakInLogsEnabled(): Boolean = prefs.getBoolean(KEY_AUTO_CLEAR_LOGS, false)

    fun setAutoClearBreakInLogsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_CLEAR_LOGS, enabled).apply()
    }

    // ---------- 10. Biometric authentication fallback ----------

    fun isBiometricFallbackEnabled(): Boolean = prefs.getBoolean(KEY_BIOMETRIC_FALLBACK, false)

    fun setBiometricFallbackEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_BIOMETRIC_FALLBACK, enabled).apply()
    }

    // ---------- OEM autostart / background-permission confirmation ----------

    fun isAutostartConfirmed(): Boolean = prefs.getBoolean(KEY_AUTOSTART_CONFIRMED, false)

    fun setAutostartConfirmed(confirmed: Boolean) {
        prefs.edit().putBoolean(KEY_AUTOSTART_CONFIRMED, confirmed).apply()
    }

    // ---------- Theme accent color ----------

    fun getAccentColorId(): String = prefs.getString(KEY_ACCENT_ID, ThemeManager.DEFAULT_ID) ?: ThemeManager.DEFAULT_ID

    fun setAccentColorId(id: String) {
        prefs.edit().putString(KEY_ACCENT_ID, id).apply()
    }

    // ---------- Disguise / launcher icon ----------

    fun getIconAliasId(): String = prefs.getString(KEY_ICON_ALIAS, IconAliasManager.DEFAULT_ALIAS) ?: IconAliasManager.DEFAULT_ALIAS

    fun setIconAliasId(id: String) {
        prefs.edit().putString(KEY_ICON_ALIAS, id).apply()
    }

    // ---------- Export / import for Backup & Restore ----------

    fun exportToMap(): Map<String, String> = mapOf(
        KEY_DECOY_MODE to isDecoyModeEnabled().toString(),
        KEY_INSTANT_RELOCK to isInstantRelockOnSwitch().toString(),
        KEY_FAKE_STORAGE_NOTIF to isFakeStorageNotificationEnabled().toString(),
        KEY_STEALTH_RECENTS to isStealthRecentsEnabled().toString(),
        KEY_ROTARY_HAPTICS to isRotaryHapticsSoundEnabled().toString(),
        KEY_AUTO_RELOCK_SECONDS to getAutoRelockSeconds().toString(),
        KEY_SHAKE_TO_LOCK to isShakeToLockEnabled().toString(),
        KEY_FLIP_TO_EXIT to isFlipToExitEnabled().toString(),
        KEY_AUTO_CLEAR_LOGS to isAutoClearBreakInLogsEnabled().toString(),
        KEY_BIOMETRIC_FALLBACK to isBiometricFallbackEnabled().toString(),
        KEY_ACCENT_ID to getAccentColorId(),
        KEY_ICON_ALIAS to getIconAliasId()
    )

    fun importFromMap(map: Map<String, String>) {
        val editor = prefs.edit()
        map[KEY_DECOY_MODE]?.toBooleanStrictOrNull()?.let { editor.putBoolean(KEY_DECOY_MODE, it) }
        map[KEY_INSTANT_RELOCK]?.toBooleanStrictOrNull()?.let { editor.putBoolean(KEY_INSTANT_RELOCK, it) }
        map[KEY_FAKE_STORAGE_NOTIF]?.toBooleanStrictOrNull()?.let { editor.putBoolean(KEY_FAKE_STORAGE_NOTIF, it) }
        map[KEY_STEALTH_RECENTS]?.toBooleanStrictOrNull()?.let { editor.putBoolean(KEY_STEALTH_RECENTS, it) }
        map[KEY_ROTARY_HAPTICS]?.toBooleanStrictOrNull()?.let { editor.putBoolean(KEY_ROTARY_HAPTICS, it) }
        map[KEY_AUTO_RELOCK_SECONDS]?.toLongOrNull()?.let { editor.putLong(KEY_AUTO_RELOCK_SECONDS, it) }
        map[KEY_SHAKE_TO_LOCK]?.toBooleanStrictOrNull()?.let { editor.putBoolean(KEY_SHAKE_TO_LOCK, it) }
        map[KEY_FLIP_TO_EXIT]?.toBooleanStrictOrNull()?.let { editor.putBoolean(KEY_FLIP_TO_EXIT, it) }
        map[KEY_AUTO_CLEAR_LOGS]?.toBooleanStrictOrNull()?.let { editor.putBoolean(KEY_AUTO_CLEAR_LOGS, it) }
        map[KEY_BIOMETRIC_FALLBACK]?.toBooleanStrictOrNull()?.let { editor.putBoolean(KEY_BIOMETRIC_FALLBACK, it) }
        map[KEY_ACCENT_ID]?.let { editor.putString(KEY_ACCENT_ID, it) }
        map[KEY_ICON_ALIAS]?.let { editor.putString(KEY_ICON_ALIAS, it) }
        editor.apply()
    }

    companion object {
        private const val KEY_DECOY_MODE = "decoy_mode_enabled"
        private const val KEY_INSTANT_RELOCK = "instant_relock_on_switch"
        private const val KEY_FAKE_STORAGE_NOTIF = "fake_storage_notification_enabled"
        private const val KEY_STEALTH_RECENTS = "stealth_recents_enabled"
        private const val KEY_ROTARY_HAPTICS = "rotary_haptics_sound_enabled"
        private const val KEY_AUTO_RELOCK_SECONDS = "auto_relock_seconds"
        private const val KEY_SHAKE_TO_LOCK = "shake_to_lock_enabled"
        private const val KEY_FLIP_TO_EXIT = "flip_to_exit_enabled"
        private const val KEY_AUTO_CLEAR_LOGS = "auto_clear_breakin_logs"
        private const val KEY_BIOMETRIC_FALLBACK = "biometric_fallback_enabled"
        private const val KEY_ACCENT_ID = "accent_color_id"
        private const val KEY_ICON_ALIAS = "icon_alias_id"
        private const val KEY_AUTOSTART_CONFIRMED = "autostart_confirmed"
    }
}
