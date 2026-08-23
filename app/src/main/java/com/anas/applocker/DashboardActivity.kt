package com.anas.applocker

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
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
        }
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
