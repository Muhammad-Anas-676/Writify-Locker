package com.anas.applocker

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.appcompat.app.AlertDialog

/**
 * Battery-optimization exemption alone isn't always enough on Infinix's XOS - it has a
 * separate "Auto-start" / "App launch" manager, plus a "Recents lock" toggle on the app
 * card, that can independently let the process get killed regardless of the standard
 * Android battery settings. There is no reliable public API to check either of those
 * states, so this can only be confirmed by the user themself - it deliberately keeps
 * asking until they explicitly say it's done, and un-confirms itself automatically if
 * the keep-alive heartbeat later finds the service dead again (see ProtectionNotifier).
 *
 * Shared by DashboardActivity (auto-prompted as part of the permission chain) and
 * SettingsActivity (manually re-openable any time from "Background Protection").
 */
object AutostartGuideHelper {

    fun show(activity: Activity) {
        val settingsStore = SettingsStore(activity)

        AlertDialog.Builder(activity)
            .setTitle("Stop XOS from killing Writify")
            .setMessage(
                "Battery optimization being off isn't enough on Infinix - XOS has its own " +
                    "separate app manager that can still force-stop Writify. Two manual steps, " +
                    "both one-time:\n\n" +
                    "1) Autostart: Settings → Apps → Writify → Battery/Autostart (or Phone " +
                    "Manager → App Management → Autostart) → turn Writify ON. Also enable " +
                    "\"Run in background\" / \"High background power\" if you see it.\n\n" +
                    "2) Recents lock: open Recent Apps, find Writify's card, long-press it and " +
                    "tap the padlock icon so swiping recents away doesn't kill it.\n\n" +
                    "Exact wording varies a bit by XOS version, but it's always under Battery " +
                    "or App Management somewhere."
            )
            .setPositiveButton("Open settings") { _, _ ->
                openAutostartSettings(activity)
                // Deliberately NOT marked confirmed here - opening the screen isn't the same
                // as actually flipping the toggle. It'll ask again next time until they tap
                // "Yes, done" below.
            }
            .setNeutralButton("Yes, I've done both") { _, _ ->
                settingsStore.setAutostartConfirmed(true)
            }
            .setNegativeButton("Remind me later", null)
            .show()
    }

    /** Best-effort: tries several known Infinix/Transsion (XOS) autostart screens across
     *  different firmware builds, falls back to the app's info page. Exact component names
     *  vary by XOS version and aren't all guaranteed to exist on every device - the written
     *  steps in the dialog above are the reliable fallback either way. */
    private fun openAutostartSettings(activity: Activity) {
        val candidates = listOf(
            ComponentName("com.transsion.phonemanager", "com.transsion.phonemanager.module.appmanager.autostart.AutoStartActivity"),
            ComponentName("com.transsion.phonemanager", "com.transsion.phonemanager.module.appmanager.ui.AppManagerActivity"),
            ComponentName("com.transsion.phonemanager", "com.transsion.phonemanager.MainActivity"),
            ComponentName("com.itel.autobootmanage", "com.itel.autobootmanage.AutoBootManageActivity"),
            ComponentName("com.transsion.phonemanager", "com.itel.autobootmanage.AutoBootManageActivity"),
            ComponentName("com.transsion.batterymanager", "com.transsion.batterymanager.ui.activity.AppSelectDetailActivity"),
            ComponentName("com.transsion.batterymanager", "com.transsion.batterymanager.ui.activity.HighPowerActivity")
        )

        for (component in candidates) {
            try {
                val intent = Intent().apply {
                    this.component = component
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                activity.startActivity(intent)
                return
            } catch (e: Exception) {
                // Try the next known component name.
            }
        }

        // Fallback: plain app info screen, from where the user can still reach battery settings.
        try {
            activity.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${activity.packageName}"))
            )
        } catch (e: Exception) { }
    }
}
