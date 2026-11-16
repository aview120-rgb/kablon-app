package com.kushertil.filter.data

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.SystemClock
import com.kushertil.filter.BuildConfig
import com.kushertil.filter.boot.PolicyAlarmReceiver
import com.kushertil.filter.vpn.FilterVpnService

/**
 * Central reconciliation between policy state and the running services.
 * Call [syncAndReconcile] on app open, on boot, and on the periodic alarm.
 */
object FilterController {

    /** Pull policy from the server (if provisioned) then align services. */
    fun syncAndReconcile(ctx: Context) {
        Thread {
            PolicyClient.sync(ctx)
            reconcile(ctx)
        }.start()
    }

    /** Start/stop the VPN to match current policy. Safe to call repeatedly. */
    fun reconcile(ctx: Context) {
        val shouldFilter = Prefs.filteringActiveNow(ctx)
        if (shouldFilter) {
            // Only start if the user has already granted the VPN consent.
            if (VpnService.prepare(ctx) == null) {
                FilterVpnService.start(ctx)
            }
        } else {
            FilterVpnService.stop(ctx)
        }
    }

    fun schedulePeriodicSync(ctx: Context) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intervalMs = BuildConfig.POLICY_SYNC_MINUTES.coerceAtLeast(1) * 60_000L
        val pi = PendingIntent.getBroadcast(
            ctx, 0,
            Intent(ctx, PolicyAlarmReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        am.setInexactRepeating(
            AlarmManager.ELAPSED_REALTIME,
            SystemClock.elapsedRealtime() + intervalMs,
            intervalMs,
            pi
        )
    }
}
