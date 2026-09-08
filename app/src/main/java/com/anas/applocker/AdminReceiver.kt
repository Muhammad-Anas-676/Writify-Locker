package com.anas.applocker

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent

/**
 * DeviceAdminReceiver — registering this component as an active Device Admin causes Android OS
 * to natively block direct uninstallation via Settings → Apps → Uninstall. The user must first
 * go through Settings → Security → Device Admins → Writify → Deactivate, and our
 * [LockAccessibilityService] intercepts that navigation path with the protection overlay.
 *
 * No extra device-policy capabilities are declared; registration alone is sufficient to engage
 * the OS-level uninstall block.
 */
class AdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        // Device Admin activated → the OS will now refuse uninstall attempts
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        // This message appears in the system confirmation dialog when the user tries to deactivate.
        return context.getString(R.string.admin_disable_warning)
    }

    override fun onDisabled(context: Context, intent: Intent) {
        // Device Admin deactivated — protection degraded; the app can now be uninstalled normally.
        // The accessibility-service overlay still intercepts the Settings path in this state,
        // but the OS-level block is gone.
    }
}
