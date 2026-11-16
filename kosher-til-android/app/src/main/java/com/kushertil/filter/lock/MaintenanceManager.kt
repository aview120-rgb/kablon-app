package com.kushertil.filter.lock

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import com.kushertil.filter.BuildConfig
import com.kushertil.filter.admin.KosherAdminReceiver
import com.kushertil.filter.data.Prefs
import com.kushertil.filter.vpn.FilterVpnService

/**
 * Development/maintenance escape hatch.
 *
 * In a production ("Netsmart-style") build the end user cannot release the
 * filter from the device — only the management server can. This object exists
 * so that, DURING DEVELOPMENT, the maintainer can fully disable the filter and
 * release device-admin by entering [BuildConfig.MAINTENANCE_CODE]
 * (the Hebrew code "א.י.ס.חסימות").
 *
 * It is guarded three ways:
 *   1. Compile-time flag BuildConfig.MAINTENANCE_ENABLED.
 *   2. A runtime "burn" flag (Prefs.disableMaintenanceForever) a provisioning
 *      step can set to permanently kill it in the field.
 *   3. Attempt throttling with exponential lockout.
 */
object MaintenanceManager {

    private const val MAX_ATTEMPTS = 5
    private const val BASE_LOCKOUT_MS = 30_000L

    fun isAvailable(ctx: Context): Boolean =
        BuildConfig.MAINTENANCE_ENABLED && !Prefs.maintenanceDisabled(ctx)

    /** Seconds remaining in a throttle lockout, or 0 if none. */
    fun lockoutRemainingSec(ctx: Context): Int {
        val remaining = Prefs.lockoutUntil(ctx) - System.currentTimeMillis()
        return if (remaining > 0) ((remaining + 999) / 1000).toInt() else 0
    }

    sealed class Outcome {
        object Released : Outcome()
        data class Wrong(val attemptsLeft: Int) : Outcome()
        data class LockedOut(val seconds: Int) : Outcome()
        object Unavailable : Outcome()
    }

    fun tryCode(ctx: Context, input: String): Outcome {
        if (!isAvailable(ctx)) return Outcome.Unavailable

        val lockSec = lockoutRemainingSec(ctx)
        if (lockSec > 0) return Outcome.LockedOut(lockSec)

        // Constant-time-ish comparison on the normalized strings.
        val expected = BuildConfig.MAINTENANCE_CODE
        if (normalize(input) == normalize(expected)) {
            Prefs.setFailCount(ctx, 0)
            Prefs.setLockoutUntil(ctx, 0)
            release(ctx)
            return Outcome.Released
        }

        val fails = Prefs.failCount(ctx) + 1
        Prefs.setFailCount(ctx, fails)
        return if (fails >= MAX_ATTEMPTS) {
            val factor = 1L shl (fails - MAX_ATTEMPTS).coerceAtMost(5)
            Prefs.setLockoutUntil(ctx, System.currentTimeMillis() + BASE_LOCKOUT_MS * factor)
            Prefs.setFailCount(ctx, 0)
            Outcome.LockedOut((BASE_LOCKOUT_MS * factor / 1000).toInt())
        } else {
            Outcome.Wrong(MAX_ATTEMPTS - fails)
        }
    }

    private fun normalize(s: String): String = s.trim()

    /** Fully stand the filter down and release device admin so it can be removed. */
    private fun release(ctx: Context) {
        Prefs.setEnabled(ctx, false)
        Prefs.setUnlockUntil(ctx, Long.MAX_VALUE)
        FilterVpnService.stop(ctx)

        val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(ctx, KosherAdminReceiver::class.java)
        if (dpm.isAdminActive(admin)) {
            try {
                dpm.removeActiveAdmin(admin)
            } catch (_: SecurityException) {
                // removeActiveAdmin only works for the admin's own package; ignore otherwise.
            }
        }
    }
}
