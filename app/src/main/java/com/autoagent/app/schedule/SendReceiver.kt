package com.autoagent.app.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class SendReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SEND) return
        val id = intent.getLongExtra(SendScheduler.EXTRA_SEND_ID, -1)
        if (id >= 0) SendScheduler.enqueueNow(context, id)
    }

    companion object {
        const val ACTION_SEND = "com.autoagent.app.SCHEDULED_SEND"
    }
}
