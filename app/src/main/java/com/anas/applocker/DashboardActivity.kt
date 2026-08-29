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
        /** Set by ProtectionNotifier's notification tap so the guide pops up immediately
         *  instead of waiting behind the rest of the permission chain. */
        const val EXTRA_SHOW_AUTOSTART_GUIDE = "show_autostart_guide"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Stealth recents protection (Settings toggle): hides the vault dashboard's real
        // content from the Recents/App-switcher thumbnail and from screenshots.
        if (SettingsStore(this).isStealthRecentsEnabled()) {
            window.setFlags(
                android.view.WindowManager.LayoutParams.FLAG_SECURE,
                android.view.WindowManager.LayoutParams.FLAG_SECURE
            )
        }
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

        if (intent.getBooleanExtra(EXTRA_SHOW_AUTOSTART_GUIDE, false)) {
            showAutostartGuide()
        } else {
            checkPermissions()
        }
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
        } else if (!SettingsStore(this).isAutostartConfirmed()) {
            showAutostartGuide()
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
                    "This lets it notice when a locked app opens, so it can ask for your PIN.\n\n" +
                    "You'll land directly on Writify's toggle - just switch it ON and press back.\n\n" +
                    "(If the toggle looks greyed out: tap the 3-dot menu on that screen -> " +
                    "\"Allow restricted setting\" first - Android blocks this by default for " +
                    "apps installed outside the Play Store.)"
            )
            .setPositiveButton("Open Settings") { _, _ ->
                openAccessibilitySettingsDirect()
            }
            .setNegativeButton("Later", null)
            .show()
    }

    /**
     * Jumps straight to Writify's own entry inside Accessibility Settings instead of the
     * generic list of every accessibility service on the device. Uses the documented
     * ":settings:show_fragment_args" / ":settings:fragment_args_key" extras that AOSP's
     * Settings app (and most OEM skins built on it) use to pre-select and scroll to a
     * specific item. Falls back to the plain Accessibility Settings list on any device/OEM
     * that ignores these extras, so it never leaves the user on a broken screen.
     */
    private fun openAccessibilitySettingsDirect() {
        val serviceComponent = ComponentName(this, LockAccessibilityService::class.java).flattenToString()
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                putExtra(":settings:fragment_args_key", serviceComponent)
                putExtra(
                    ":settings:show_fragment_args",
                    android.os.Bundle().apply {
                        putString(":settings:fragment_args_key", serviceComponent)
                    }
                )
            }
            startActivity(intent)
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            } catch (e2: ActivityNotFoundException) { }
        }
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

    /** See AutostartGuideHelper for why this can't be a simple enforced permission check. */
    private fun showAutostartGuide() {
        AutostartGuideHelper.show(this)
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
