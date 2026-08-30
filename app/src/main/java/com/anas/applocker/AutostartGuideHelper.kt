package com.anas.applocker

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.appcompat.app.AlertDialog

/**
 * Battery-optimization exemption alone isn't always enough on Infinix's XOS - it has its
 * own separate app manager ("Phone Master" / "Battery Lab") plus a "Recents lock" toggle
 * on the app card that can independently let the process get killed regardless of the
 * standard Android battery settings. There is no reliable public API to check any of
 * these states, so this can only be confirmed by the user themself - it deliberately
 * keeps asking until they explicitly say it's done, and un-confirms itself automatically
 * if the keep-alive heartbeat later finds the service dead again (see ProtectionNotifier).
 *
 * Presented as a short multi-step wizard rather than one wall of text: a general
 * Android section that applies to every device, followed by a dedicated Infinix Hot 8
 * Lite (XOS / Transsion) section with its own five concrete steps, since that's the
 * device this app has actually been tuned against.
 *
 * Shared by DashboardActivity (auto-prompted as part of the permission chain) and
 * SettingsActivity (manually re-openable any time from "Background Protection").
 */
object AutostartGuideHelper {

    private data class Step(
        val title: String,
        val message: String,
        /** If non-null, shown as an extra button that jumps straight to the relevant screen. */
        val settingsAction: (() -> Unit)? = null
    )

    fun show(activity: Activity) {
        val steps = buildSteps(activity)
        showStep(activity, steps, index = 0)
    }

    private fun buildSteps(activity: Activity): List<Step> = listOf(
        // ---- General Android (applies to every device, every manufacturer) ----
        Step(
            title = "Step 1 of 6 — Accessibility",
            message = "Make sure Writify's Accessibility service is turned ON. This is what " +
                "lets it detect when a locked app opens.\n\nSettings → Accessibility → " +
                "Writify → turn it on.",
            settingsAction = { openAccessibilitySettings(activity) }
        ),
        Step(
            title = "Step 2 of 6 — Unrestricted battery",
            message = "Set Writify's battery usage to \"Unrestricted\" (not \"Optimized\") so " +
                "Android doesn't freeze it in the background.\n\nSettings → Apps → Writify → " +
                "Battery → Unrestricted.",
            settingsAction = { openBatterySettings(activity) }
        ),
        Step(
            title = "Step 3 of 6 — Lock it in Recents",
            message = "Open Recent Apps, find Writify's card, and tap the padlock icon " +
                "(long-press the card first if you don't see it right away) so swiping " +
                "Recents away doesn't kill it."
        ),
        // ---- Infinix Hot 8 Lite / XOS / Transsion specific ----
        Step(
            title = "Step 4 of 6 — Remove from Freezer (Infinix/XOS)",
            message = "XOS has its own separate \"Freezer\" that can suspend apps even when " +
                "Android's own battery settings are fine.\n\nPhone Manager → Freezer → make " +
                "sure Writify is NOT in the frozen list (remove it if it is)."
        ),
        Step(
            title = "Step 5 of 6 — Auto-start & Power Marathon (Infinix/XOS)",
            message = "Two more XOS-specific toggles, both one-time:\n\n" +
                "• Phone Master → Auto-start Management → turn Writify ON.\n\n" +
                "• Battery Lab / Power Marathon → Battery Optimization for Writify → set to " +
                "\"Not Optimized\".",
            settingsAction = { openInfinixAutostartSettings(activity) }
        ),
        Step(
            title = "Step 6 of 6 — Auto-reset permissions",
            message = "Last one: turn OFF \"Pause app activity if unused\" for Writify, so " +
                "Android doesn't auto-revoke its permissions after a few days of not opening " +
                "it directly.\n\nSettings → Apps → Writify → (⋮ menu) → turn off \"Pause app " +
                "activity if unused\"."
        )
    )

    private fun showStep(activity: Activity, steps: List<Step>, index: Int) {
        if (activity.isFinishing) return
        val step = steps[index]
        val isLast = index == steps.lastIndex
        val settingsStore = SettingsStore(activity)

        val builder = AlertDialog.Builder(activity)
            .setTitle(step.title)
            .setMessage(step.message)
            .setCancelable(false)
            .setNegativeButton(if (index == 0) "Remind me later" else "Back") { _, _ ->
                if (index > 0) showStep(activity, steps, index - 1)
            }
            .setPositiveButton(if (isLast) "Done — I've done all steps" else "Next") { _, _ ->
                if (isLast) {
                    settingsStore.setAutostartConfirmed(true)
                } else {
                    showStep(activity, steps, index + 1)
                }
            }

        if (step.settingsAction != null) {
            builder.setNeutralButton("Open settings") { _, _ ->
                step.settingsAction.invoke()
                // Deliberately don't auto-advance here - opening the screen isn't the same
                // as actually flipping the toggle, so the same step is shown again on return.
                showStep(activity, steps, index)
            }
        }

        builder.show()
    }

    private fun openAccessibilitySettings(activity: Activity) {
        try {
            activity.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (e: ActivityNotFoundException) { }
    }

    private fun openBatterySettings(activity: Activity) {
        try {
            activity.startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:${activity.packageName}")
                )
            )
        } catch (e: ActivityNotFoundException) { }
    }

    /** Best-effort: tries several known Infinix/Transsion (XOS) autostart screens across
     *  different firmware builds, falls back to the app's info page. Exact component names
     *  vary by XOS version and aren't all guaranteed to exist on every device - the written
     *  steps in the dialogs above are the reliable fallback either way. */
    private fun openInfinixAutostartSettings(activity: Activity) {
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
