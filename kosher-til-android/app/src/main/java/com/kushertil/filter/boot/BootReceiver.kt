package com.kushertil.filter.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.kushertil.filter.data.FilterController

/** Re-arm filtering after reboot or app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        FilterController.schedulePeriodicSync(context)
        FilterController.syncAndReconcile(context)
    }
}
