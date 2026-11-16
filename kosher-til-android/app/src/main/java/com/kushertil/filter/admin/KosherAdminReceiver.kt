package com.kushertil.filter.admin

import android.app.admin.DeviceAdminReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.kushertil.filter.R

/**
 * Device-admin receiver. Holding the device-admin role means the app cannot be
 * uninstalled until the admin is deactivated first, which deters casual removal.
 *
 * NOTE (honest limitation): device admin is a *deterrent*, not an absolute lock.
 * A determined user with the device in hand can deactivate admin from Settings,
 * boot to safe mode, or factory reset. Truly enforced, unremovable policy
 * requires Device Owner provisioning (fresh device via QR/afw#setup or an EMM).
 * See README.
 */
class KosherAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        // Admin granted.
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        // Shown when the user tries to deactivate admin.
        return context.getString(R.string.admin_explanation)
    }

    companion object {
        fun component(ctx: Context) = ComponentName(ctx, KosherAdminReceiver::class.java)
    }
}
