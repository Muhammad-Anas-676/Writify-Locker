package com.anas.applocker

import android.content.Context

/**
 * Every user-facing preference from the Settings screen lives here, in one plain
 * (non-encrypted) SharedPreferences file. Nothing sensitive is stored in it - no PINs,
 * no vault contents - just toggles and small numbers, so it's safe to include in the
 * Backup & Restore JSON as-is.
 */
class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)

    // ---------- Decoy / stealth mode ----------

    fun isDecoyModeEnabled(): Boolean = prefs.getBoolean(KEY_DECOY_MODE, true)

    fun setDecoyModeEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DECOY_MODE, enabled).apply()
    }

    // ---------- Auto re-lock timer ----------

    fun getAutoRelockSeconds(): Long = prefs.getLong(KEY_AUTO_RELOCK_SECONDS, 15L)

    fun setAutoRelockSeconds(seconds: Long) {
        prefs.edit().putLong(KEY_AUTO_RELOCK_SECONDS, seconds).apply()
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
        KEY_AUTO_RELOCK_SECONDS to getAutoRelockSeconds().toString(),
        KEY_ACCENT_ID to getAccentColorId(),
        KEY_ICON_ALIAS to getIconAliasId()
    )

    fun importFromMap(map: Map<String, String>) {
        val editor = prefs.edit()
        map[KEY_DECOY_MODE]?.toBooleanStrictOrNull()?.let { editor.putBoolean(KEY_DECOY_MODE, it) }
        map[KEY_AUTO_RELOCK_SECONDS]?.toLongOrNull()?.let { editor.putLong(KEY_AUTO_RELOCK_SECONDS, it) }
        map[KEY_ACCENT_ID]?.let { editor.putString(KEY_ACCENT_ID, it) }
        map[KEY_ICON_ALIAS]?.let { editor.putString(KEY_ICON_ALIAS, it) }
        editor.apply()
    }

    companion object {
        private const val KEY_DECOY_MODE = "decoy_mode_enabled"
        private const val KEY_AUTO_RELOCK_SECONDS = "auto_relock_seconds"
        private const val KEY_ACCENT_ID = "accent_color_id"
        private const val KEY_ICON_ALIAS = "icon_alias_id"
    }
}
