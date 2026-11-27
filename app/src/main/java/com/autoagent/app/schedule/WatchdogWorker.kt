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
                SendWorker.finishSend(app, w.id, com.autoagent.app.data.SendStatus.ABORTED,
                    com.autoagent.app.util.L.t("Phone was not unlocked within 60 minutes; not sent.", "הטלפון לא נפתח תוך 60 דקות; לא נשלח."), w.recipientLabel)
            }
        }
        val waitingService = app.db.sends().byStatus(com.autoagent.app.data.SendStatus.WAITING_SERVICE)
        for (w in waitingService) {
            if (now - w.sendAt > SendWorker.UNLOCK_LIMIT_MS) {
                SendWorker.finishSend(app, w.id, com.autoagent.app.data.SendStatus.FAILED,
                    com.autoagent.app.util.L.t("Accessibility service did not come back within 60 minutes; not sent.", "שירות הנגישות לא חזר תוך 60 דקות; לא נשלח."), w.recipientLabel)
            }
        }
        // Accessibility health check + recovery attempt (rate-limited) when something depends on it.
        val needsService = waitingService.isNotEmpty() || app.db.sends().pending().any { it.channel != com.autoagent.app.data.SendChannel.SMS }
        val health = com.autoagent.app.access.AccessibilityHealth.refresh(app)
        if (needsService && !health.ready) com.autoagent.app.access.AccessibilityHealth.recover(app)
        app.db.logs().deleteOlderThan(now - 30L * 86_400_000L)
        com.autoagent.app.sync.SyncScheduler.syncNow(app, "watchdog")
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
