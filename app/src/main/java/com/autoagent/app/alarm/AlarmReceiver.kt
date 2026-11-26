package com.autoagent.app.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.autoagent.app.AutoAgentApp
import com.autoagent.app.service.AgentStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FIRE) return
        val id = intent.getLongExtra(AlarmScheduler.EXTRA_ALARM_ID, -1)
        if (id < 0) return
        val pending = goAsync()
        val app = context.applicationContext as AutoAgentApp
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val alarm = app.db.alarms().get(id)
                if (alarm != null && alarm.enabled) {
                    app.db.alarms().update(alarm.copy(enabled = false))
                    val svc = Intent(context, AlarmRingService::class.java)
                        .putExtra(AlarmScheduler.EXTRA_ALARM_ID, id)
                        .putExtra(AlarmRingService.EXTRA_LABEL, alarm.label)
                    ContextCompat.startForegroundService(context, svc)
                    app.log("alarm", "Alarm fired: ${alarm.label}", true)
                }
                AgentStatus.refresh(context)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_FIRE = "com.autoagent.app.ALARM_FIRE"
    }
}
