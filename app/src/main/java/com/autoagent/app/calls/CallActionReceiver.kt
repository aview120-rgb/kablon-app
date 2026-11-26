package com.autoagent.app.calls

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(EXTRA_ID) ?: return
        when (intent.action) {
            ACTION_ANSWER -> {
                CallManager.answer(id)
                context.startActivity(Intent(context, InCallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            ACTION_REJECT -> CallManager.reject(id)
            ACTION_HANGUP -> CallManager.hangUp(id)
        }
    }

    companion object {
        const val ACTION_ANSWER = "com.autoagent.app.CALL_ANSWER"
        const val ACTION_REJECT = "com.autoagent.app.CALL_REJECT"
        const val ACTION_HANGUP = "com.autoagent.app.CALL_HANGUP"
        private const val EXTRA_ID = "call_id"

        fun pending(context: Context, action: String, id: String): PendingIntent =
            PendingIntent.getBroadcast(
                context, action.hashCode(),
                Intent(context, CallActionReceiver::class.java).setAction(action).putExtra(EXTRA_ID, id),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
    }
}
