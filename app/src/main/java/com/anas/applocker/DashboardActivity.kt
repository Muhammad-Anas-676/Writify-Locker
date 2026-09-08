package com.anas.applocker

import android.app.admin.DevicePolicyManager
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

    private lateinit var dpm: DevicePolicyManager
    private lateinit var adminComponent: ComponentName

    companion object {
        private const val KEY_AUTOSTART_GUIDE_SHOWN = "autostart_guide_shown"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dashboard)

        dpm            = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        adminComponent = ComponentName(this, AdminReceiver::class.java)

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

        findViewById<TextView>(R.id.settingsButton).setOnClickListener {
            showSettingsMenu()
        }

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

        // Re-check every time the user comes back from Settings so prompts
        // don't nag once permissions are actually granted.
        checkPermissions()
    }

    private fun showFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, fragment)
            .commit()
    }

    // ──────────────────────────────────────────────────────────────
    // Permission / protection check chain
    // ──────────────────────────────────────────────────────────────

    /**
     * Walks through all required permissions in priority order.
     * Each prompt is shown once per check; the next call (after the user returns from Settings)
     * advances to the next unsatisfied requirement.
     *
     * Order:
     *   1. Accessibility Service     — core app-locking engine
     *   2. Overlay ("draw over apps") — zero-flash instant overlay
     *   3. Battery optimization exempt — prevents OEM process killers
     *   4. Device Admin              — OS-level uninstall block (NEW)
     *   5. Autostart guide           — one-time manual step for Infinix/XOS
     */
    private fun checkPermissions() {
        when {
            !isAccessibilityServiceEnabled()  -> promptEnableAccessibility()
            !isOverlayPermissionGranted()     -> promptEnableOverlay()
            !isBatteryOptimizationExempt()    -> promptDisableBatteryOptimization()
            !isDeviceAdminActive()            -> promptActivateDeviceAdmin()
            else                              -> maybeShowAutostartGuide()
        }
    }

    // ── Checkers ──────────────────────────────────────────────────

    private fun isAccessibilityServiceEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.contains(packageName)
    }

    private fun isOverlayPermissionGranted(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(this)
        else true

    private fun isBatteryOptimizationExempt(): Boolean =
        (getSystemService(POWER_SERVICE) as PowerManager)
            .isIgnoringBatteryOptimizations(packageName)

    /**
     * Returns true when this app is an active Device Admin.
     * While active, Android OS natively blocks uninstallation via Settings → Apps.
     * The user must explicitly deactivate admin first — our [LockAccessibilityService]
     * intercepts that navigation path with the protection overlay.
     */
    private fun isDeviceAdminActive(): Boolean = dpm.isAdminActive(adminComponent)

    // ── Prompts ───────────────────────────────────────────────────

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
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
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
                    startActivity(
                        Intent(
                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:$packageName")
                        )
                    )
                } catch (_: ActivityNotFoundException) {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            }
            .setNegativeButton("Later", null)
            .show()
    }

    /**
     * Prompts the user to activate Device Admin for this app.
     *
     * Once active, Android will refuse to uninstall Writify through the normal
     * Settings → Apps → Uninstall flow. The user would first have to go to
     * Settings → Security → Device Admins → Writify → Deactivate, and our
     * [LockAccessibilityService] intercepts that screen with the protection overlay
     * so the action still requires the real authorization credential.
     */
    private fun promptActivateDeviceAdmin() {
        AlertDialog.Builder(this)
            .setTitle("Enable Anti-Uninstall Protection")
            .setMessage(
                "Activate Writify as a Device Administrator to prevent unauthorized removal.\n\n" +
                "While active, Android's own uninstall system will block attempts to uninstall " +
                "the app without your authorization credential."
            )
            .setPositiveButton("Activate") { _, _ ->
                val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                    putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
                    putExtra(
                        DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                        getString(R.string.admin_activation_explanation)
                    )
                }
                startActivity(intent)
            }
            .setNegativeButton("Later", null)
            .show()
    }

    /**
     * One-time manual guide for Infinix / XOS devices, which have a proprietary
     * Auto-Start manager that can independently kill background services regardless
     * of the standard Android battery-optimization setting. No public API exists to
     * read or set this toggle programmatically, so this is shown as a guide only.
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

    /** Best-effort: tries known Infinix/XOS autostart screens, falls back to App Info. */
    private fun openAutostartSettings() {
        val candidates = listOf(
            ComponentName(
                "com.transsion.phonemanager",
                "com.transsion.phonemanager.module.appmanager.autostart.AutoStartActivity"
            ),
            ComponentName(
                "com.transsion.phonemanager",
                "com.itel.autobootmanage.AutoBootManageActivity"
            ),
            ComponentName(
                "com.transsion.batterymanager",
                "com.transsion.batterymanager.ui.activity.AppSelectDetailActivity"
            ),
        )

        for (component in candidates) {
            try {
                startActivity(Intent().apply {
                    this.component = component
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                })
                return
            } catch (_: Exception) { /* try next */ }
        }

        // Fallback: plain App Info, from where the user can still reach battery settings
        try {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$packageName")
                )
            )
        } catch (_: Exception) { }
    }

    // ──────────────────────────────────────────────────────────────
    // Settings menu
    // ──────────────────────────────────────────────────────────────

    private fun showSettingsMenu() {
        val pm     = PinManager(this)
        val format = pm.getCredentialFormat()
        val formatLabel = if (format == PinManager.CredentialFormat.ALPHABETIC) "Alphabetic Password" else "Numeric PIN"
        val adminStatus = if (isDeviceAdminActive()) "✓ Active" else "✗ Inactive"

        val options = arrayOf(
            "🔑  Change Credential",
            "🛡  Device Admin — $adminStatus",
            "ℹ  About"
        )

        AlertDialog.Builder(this)
            .setTitle("Settings")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> startChangeCredentialFlow(pm)
                    1 -> showDeviceAdminSettings()
                    2 -> showAbout(formatLabel)
                }
            }
            .show()
    }

    // ── Change Credential ─────────────────────────────────────────

    /**
     * 3-step flow:
     *   Step 1 — verify current real credential
     *   Step 2 — choose new format
     *   Step 3 — enter new real + fake credential
     */
    private fun startChangeCredentialFlow(pm: PinManager) {
        val layout  = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(60, 32, 60, 8)
        }
        val currentInput = android.widget.EditText(this).apply {
            inputType = pm.getRealInputType()
            hint = "Current credential"
        }
        layout.addView(currentInput)

        AlertDialog.Builder(this)
            .setTitle("Verify Identity")
            .setMessage("Enter your current real credential to continue.")
            .setView(layout)
            .setPositiveButton("Verify") { _, _ ->
                if (pm.check(currentInput.text.toString()) == PinManager.PinResult.REAL) {
                    showNewCredentialDialog(pm)
                } else {
                    AlertDialog.Builder(this)
                        .setTitle("Incorrect")
                        .setMessage("Current credential did not match. Credential not changed.")
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showNewCredentialDialog(pm: PinManager) {
        // Format picker + new real + new fake
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(60, 16, 60, 8)
        }

        // Format radio group
        val formatGroup = android.widget.RadioGroup(this).apply {
            orientation = android.widget.RadioGroup.HORIZONTAL
        }
        val btnNumeric = android.widget.RadioButton(this).apply {
            text = "Numeric PIN"
            id  = android.view.View.generateViewId()
            isChecked = pm.getCredentialFormat() == PinManager.CredentialFormat.NUMERIC
        }
        val btnAlpha = android.widget.RadioButton(this).apply {
            text = "Alphabetic Password"
            id  = android.view.View.generateViewId()
            isChecked = pm.getCredentialFormat() == PinManager.CredentialFormat.ALPHABETIC
        }
        formatGroup.addView(btnNumeric)
        formatGroup.addView(btnAlpha)
        layout.addView(formatGroup)

        // Credential inputs
        val realInput = android.widget.EditText(this).apply {
            inputType = pm.getRealInputType()
            hint = "New real credential"
        }
        val fakeInput = android.widget.EditText(this).apply {
            inputType = pm.getRealInputType()
            hint = "New fake credential"
        }
        layout.addView(realInput)
        layout.addView(fakeInput)

        // Update input type when format changes
        formatGroup.setOnCheckedChangeListener { _, checkedId ->
            val newType = if (checkedId == btnAlpha.id) {
                android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            } else {
                android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            }
            realInput.inputType = newType
            fakeInput.inputType = newType
        }

        AlertDialog.Builder(this)
            .setTitle("Set New Credential")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val newReal = realInput.text.toString()
                val newFake = fakeInput.text.toString()
                val selectedFormat = if (btnAlpha.isChecked)
                    PinManager.CredentialFormat.ALPHABETIC
                else
                    PinManager.CredentialFormat.NUMERIC
                val minLen = if (selectedFormat == PinManager.CredentialFormat.ALPHABETIC) 6 else 4

                when {
                    newReal.length < minLen || newFake.length < minLen ->
                        showToast("Both credentials need at least $minLen characters")
                    newReal == newFake ->
                        showToast("Real and fake credentials must be different")
                    else -> {
                        pm.setupCredentials(newReal, newFake, selectedFormat)
                        AlertDialog.Builder(this)
                            .setTitle("Done")
                            .setMessage("Credential updated successfully.")
                            .setPositiveButton("OK", null)
                            .show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── Device Admin settings ─────────────────────────────────────

    private fun showDeviceAdminSettings() {
        val isActive = isDeviceAdminActive()
        val status   = if (isActive) "✓ Active — uninstall is blocked by Android OS" else "✗ Inactive — uninstall is NOT protected"
        val btnLabel = if (isActive) "Deactivate" else "Activate"

        AlertDialog.Builder(this)
            .setTitle("Device Admin Protection")
            .setMessage(
                "$status\n\n" +
                "While active, Android refuses to uninstall Writify through the normal " +
                "Settings → Apps flow. Deactivation requires passing the protection overlay."
            )
            .setPositiveButton(btnLabel) { _, _ ->
                if (isActive) {
                    // Takes user to the system Device Admin management screen
                    val intent = Intent(android.app.admin.DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                        // Opening device admin settings page for this component
                    }
                    startActivity(
                        Intent(Settings.ACTION_SECURITY_SETTINGS)
                    )
                } else {
                    promptActivateDeviceAdmin()
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    // ── About ─────────────────────────────────────────────────────

    private fun showAbout(formatLabel: String) {
        AlertDialog.Builder(this)
            .setTitle("Writify App Locker")
            .setMessage(
                "Version: v4.2 Refactored\n\n" +
                "Credential type: $formatLabel\n\n" +
                "Protection layers:\n" +
                "  • Accessibility service app-lock\n" +
                "  • Inverted-decoy overlay authentication\n" +
                "  • Settings & uninstaller interception\n" +
                "  • Device Admin uninstall block\n" +
                "  • Battery optimization exemption"
            )
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showToast(msg: String) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    // ──────────────────────────────────────────────────────────────
    // Break-in log
    // ──────────────────────────────────────────────────────────────

    private fun showBreakInLog() {
        val pm  = PinManager(this)
        val log = pm.getBreakInLog()

        val message = if (log.isEmpty()) {
            "No failed authorization attempts recorded."
        } else {
            val fmt = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())
            log.joinToString("\n") { fmt.format(Date(it)) }
        }

        AlertDialog.Builder(this)
            .setTitle("Break-in Log")
            .setMessage(message)
            .setPositiveButton("Close", null)
            .setNegativeButton("Clear Log") { _, _ -> pm.clearBreakInLog() }
            .show()
    }
}
