package com.autoagent.app.schedule

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.autoagent.app.AutoAgentApp
import com.autoagent.app.alarm.AlarmScheduler
import com.autoagent.app.service.AgentStatus
import java.util.concurrent.TimeUnit

/** Periodic safety net: re-arms anything pending (e.g. after force-stop) and prunes old inbox entries. */
class WatchdogWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as AutoAgentApp
        val now = System.currentTimeMillis()
        app.db.alarms().enabled().filter { it.triggerAt > now }.forEach { AlarmScheduler.schedule(app, it) }
        SendScheduler.rearmAll(app)
        for (w in app.db.sends().byStatus(com.autoagent.app.data.SendStatus.WAITING_UNLOCK)) {
            if (now - w.sendAt > SendWorker.UNLOCK_LIMIT_MS) {
                app.db.sends().setStatus(w.id, com.autoagent.app.data.SendStatus.ABORTED,
                    com.autoagent.app.util.L.t("Phone was not unlocked within 60 minutes; not sent.", "הטלפון לא נפתח תוך 60 דקות; לא נשלח."))
            }
        }
        app.db.inbox().deleteOlderThan(now - 30L * 86_400_000L)
        AgentStatus.refresh(app)
        return Result.success()
    }

    companion object {
        fun ensureScheduled(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "watchdog",
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<WatchdogWorker>(30, TimeUnit.MINUTES).build(),
            )
        }
    }
}
