package com.autoagent.app.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.autoagent.app.AutoAgentApp
import com.autoagent.app.schedule.SendScheduler
import com.autoagent.app.service.AgentStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Re-arms alarms and scheduled sends after reboot, app update, or clock change. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in ACTIONS) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                rearmAll(context)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private val ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED",
        )

        suspend fun rearmAll(context: Context) {
            val app = context.applicationContext as AutoAgentApp
            val now = System.currentTimeMillis()
            var alarms = 0
            for (a in app.db.alarms().enabled()) {
                if (a.triggerAt < now - 60_000) {
                    app.db.alarms().update(a.copy(enabled = false))
                    app.log("alarm", com.autoagent.app.util.L.t("Missed while phone was off: ${a.label}", "פוספס כשהטלפון היה כבוי: ${a.label}"), false)
                } else if (AlarmScheduler.schedule(context, a)) alarms++
            }
            val sends = SendScheduler.rearmAll(context)
            app.log("boot", com.autoagent.app.util.L.t("Re-armed $alarms alarm(s) and $sends scheduled send(s)", "הופעלו מחדש $alarms שעונים ו-$sends שליחות מתוזמנות"), true)
            AgentStatus.refresh(context)
        }
    }
}
