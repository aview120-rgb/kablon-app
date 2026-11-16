package com.kushertil.filter.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.kushertil.filter.data.FilterController

/** Periodic policy pull + service reconciliation (fired by AlarmManager). */
class PolicyAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        FilterController.syncAndReconcile(context)
    }
}
