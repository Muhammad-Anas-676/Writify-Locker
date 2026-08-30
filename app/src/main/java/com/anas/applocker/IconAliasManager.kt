package com.anas.applocker

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * Swaps which activity-alias (defined in AndroidManifest.xml) is the enabled launcher
 * entry point, so the home-screen name changes without needing a separate app or an
 * app restart. All aliases currently point at the same MainActivity and share
 * @mipmap/ic_launcher - only the label differs for now. Give each alias its own
 * drawable later (and update AndroidManifest.xml's android:icon per alias) for a full
 * icon swap, not just a name swap.
 */
object IconAliasManager {

    data class AliasOption(val id: String, val label: String, val className: String)

    val OPTIONS = listOf(
        AliasOption("writify", "Writify", "com.anas.applocker.alias.WritifyAlias"),
        AliasOption("calculator", "Calculator", "com.anas.applocker.alias.CalculatorAlias"),
        AliasOption("file_manager", "File Manager", "com.anas.applocker.alias.FileManagerAlias"),
        AliasOption("notes", "Notes", "com.anas.applocker.alias.NotesAlias")
    )

    const val DEFAULT_ALIAS = "writify"

    /** Enables [id]'s alias and disables every other one, so exactly one launcher icon shows. */
    fun setActiveAlias(context: Context, id: String) {
        val pm = context.packageManager
        OPTIONS.forEach { option ->
            val component = ComponentName(context, option.className)
            val state = if (option.id == id) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }
            try {
                pm.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
            } catch (e: Exception) { }
        }
        SettingsStore(context).setIconAliasId(id)
    }

    /** Applies whatever alias is stored in Settings - call once on app start-up. */
    fun applyStoredAlias(context: Context) {
        setActiveAlias(context, SettingsStore(context).getIconAliasId())
    }
}
