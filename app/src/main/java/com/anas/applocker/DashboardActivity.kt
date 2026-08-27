package com.anas.applocker

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.google.android.material.tabs.TabLayout
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DashboardActivity : AppCompatActivity() {

    companion object {
        private const val KEY_AUTOSTART_GUIDE_SHOWN = "autostart_guide_shown"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dashboard)

        showFragment(AppsListFragment())

        val tabLayout = findViewById<TabLayout>(R.id.tabLayout)
        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                when (tab.position) {
                    0 -> showFragment(AppsListFragment())
                    1 -> showFragment(VaultFragment())
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })

        findViewById<TextView>(R.id.breakInLogButton).setOnClickListener {
            showBreakInLog()
        }

        findViewById<android.widget.ImageView>(R.id.settingsButton).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        ThemeManager.applyToTabLayout(this, tabLayout)
        ThemeManager.applyToTextView(this, findViewById(R.id.breakInLogButton))

        checkPermissions()
    }

    override fun onResume() {
        super.onResume()

        if (VaultApplication.needsReauth) {
            VaultApplication.needsReauth = false
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        // Re-check every time the user comes back from Settings, so the
        // prompts don't nag once permissions are actually granted.
        checkPermissions()

        // Accent color may have just been changed in the Settings screen.
        ThemeManager.applyToTabLayout(this, findViewById(R.id.tabLayout))
        ThemeManager.applyToTextView(this, findViewById(R.id.breakInLogButton))
    }

    private fun showFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, fragment)
            .commit()
    }

    private fun checkPermissions() {
        if (!isAccessibilityServiceEnabled()) {
            promptEnableAccessibility()
        } else if (!isOverlayPermissionGranted()) {
            promptEnableOverlay()
        } else if (!isBatteryOptimizationExempt()) {
            promptDisableBatteryOptimization()
        } else if (AlarmScheduler.needsExactAlarmPermission(this)) {
            promptEnableExactAlarms()
        } else {
            maybeShowAutostartGuide()
        }
    }

    /**
     * Android 12+ gates precise alarm timing behind a special user-granted permission.
     * Without it, the 2.5-minute keep-alive heartbeat still runs, just less precisely
     * (the OS may batch/delay it), so this is asked for but not force-blocking.
     */
    private fun promptEnableExactAlarms() {
        AlertDialog.Builder(this)
            .setTitle("One more step")
            .setMessage(
                "For the most reliable background protection, allow Writify to schedule " +
                    "exact alarms. Without it, the keep-alive check still runs, just a little " +
                    "less precisely."
            )
            .setPositiveButton("Open Settings") { _, _ ->
                try {
                    startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM))
                } catch (e: ActivityNotFoundException) { }
            }
            .setNegativeButton("Later", null)
            .show()
    }

    /**
     * If Android is still allowed to "optimize" (i.e. freeze/kill) this app in the background,
     * the accessibility service's process can get killed after the device has been idle a
     * while - which on some phones also silently flips the Accessibility toggle off. Exempting
     * the app fixes most of this.
     */
    private fun isBatteryOptimizationExempt(): Boolean {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        return powerManager.isIgnoringBatteryOptimizations(packageName)
    }

    /** App-lock detection only fires once the user has manually enabled our Accessibility Service. */
    private fun isAccessibilityServiceEnabled(): Boolean {
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledServices.contains(packageName)
    }

    /** Needed so the lock screen can be drawn instantly, with no flash of the locked app. */
    private fun isOverlayPermissionGranted(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else true
    }

    private fun promptEnableAccessibility() {
        AlertDialog.Builder(this)
            .setTitle("One more step")
            .setMessage(
                "To actually lock apps, Writify needs Accessibility permission. " +
                    "This lets it notice when a locked app opens, so it can ask for your PIN."
            )
            .setPositiveButton("Open Settings") { _, _ ->
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun promptEnableOverlay() {
        AlertDialog.Builder(this)
            .setTitle("One more step")
            .setMessage(
                "Writify also needs \"Display over other apps\" permission. Without it, " +
                    "a locked app can briefly flash on screen before the PIN prompt appears."
            )
            .setPositiveButton("Open Settings") { _, _ ->
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun promptDisableBatteryOptimization() {
        AlertDialog.Builder(this)
            .setTitle("One more step")
            .setMessage(
                "Android is still allowed to freeze Writify in the background to save battery. " +
                    "This is the main reason the lock service can stop working after an hour or two. " +
                    "Allow Writify to run unrestricted in the background."
            )
            .setPositiveButton("Allow") { _, _ ->
                try {
                    val intent = Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName")
                    )
                    startActivity(intent)
                } catch (e: ActivityNotFoundException) {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            }
            .setNegativeButton("Later", null)
            .show()
    }

    /**
     * Battery-optimization exemption alone isn't always enough on Infinix's XOS - it has a
     * separate "Auto-start" / "App launch" manager that can independently kill background
     * services regardless of the standard Android battery settings. There is no reliable public
     * API to check this setting's state, so this can only be shown as a one-time manual guide
     * rather than an enforced permission check like the others above.
     */
    private fun maybeShowAutostartGuide() {
        val prefs = getSharedPreferences("onboarding", MODE_PRIVATE)
        if (prefs.getBoolean(KEY_AUTOSTART_GUIDE_SHOWN, false)) return

        AlertDialog.Builder(this)
            .setTitle("Last step (Infinix / XOS devices)")
            .setMessage(
                "For the lock to never switch off, also open your phone's battery settings and " +
                    "set Writify to launch automatically and run in the background:\n\n" +
                    "Settings → Battery → App launch (or App management → Autostart) → find Writify " +
                    "→ turn OFF \"Manage automatically\" → enable Auto-launch, Secondary launch, " +
                    "and Run in background.\n\n" +
                    "This is a one-time manual step - the system doesn't let apps set this for themselves."
            )
            .setPositiveButton("Open settings") { _, _ ->
                openAutostartSettings()
                prefs.edit().putBoolean(KEY_AUTOSTART_GUIDE_SHOWN, true).apply()
            }
            .setNegativeButton("Got it") { _, _ ->
                prefs.edit().putBoolean(KEY_AUTOSTART_GUIDE_SHOWN, true).apply()
            }
            .show()
    }

    /** Best-effort: tries known Infinix/XOS autostart screens, falls back to the app's info page. */
    private fun openAutostartSettings() {
        val candidates = listOf(
            ComponentName("com.transsion.phonemanager", "com.transsion.phonemanager.module.appmanager.autostart.AutoStartActivity"),
            ComponentName("com.transsion.phonemanager", "com.itel.autobootmanage.AutoBootManageActivity"),
            ComponentName("com.transsion.batterymanager", "com.transsion.batterymanager.ui.activity.AppSelectDetailActivity")
        )

        for (component in candidates) {
            try {
                val intent = Intent().apply {
                    this.component = component
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(intent)
                return
            } catch (e: Exception) {
                // Try the next known component name.
            }
        }

        // Fallback: plain app info screen, from where the user can still reach battery settings.
        try {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            )
        } catch (e: Exception) { }
    }

    private fun showBreakInLog() {
        val pinManager = PinManager(this)
        val log = pinManager.getBreakInLog()

        val message = if (log.isEmpty()) {
            "No failed PIN attempts recorded."
        } else {
            val formatter = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())
            log.joinToString("\n") { formatter.format(Date(it)) }
        }

        AlertDialog.Builder(this)
            .setTitle("Break-in Log")
            .setMessage(message)
            .setPositiveButton("Close", null)
            .setNegativeButton("Clear Log") { _, _ -> pinManager.clearBreakInLog() }
            .show()
    }
}
