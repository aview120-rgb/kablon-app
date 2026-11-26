package com.autoagent.app.schedule

import com.autoagent.app.util.L
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.autoagent.app.AutoAgentApp
import com.autoagent.app.R
import com.autoagent.app.access.AutomationService
import com.autoagent.app.data.SendChannel
import com.autoagent.app.data.SendStatus
import com.autoagent.app.service.AgentStatus
import com.autoagent.app.util.Notifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class SendWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val n = NotificationCompat.Builder(applicationContext, Notifier.CH_STATUS)
            .setSmallIcon(R.drawable.ic_stat_agent)
            .setContentTitle(L.t("AutoAgent is sending a scheduled message", "AutoAgent שולח הודעה מתוזמנת"))
            .setOngoing(true)
            .build()
        // Only used below Android 12, where expedited work runs as a foreground service without a type.
        return ForegroundInfo(Notifier.ID_SEND_WORK, n)
    }

    override suspend fun doWork(): Result {
        val app = applicationContext as AutoAgentApp
        val id = inputData.getLong(SendScheduler.EXTRA_SEND_ID, -1)
        val job = app.db.sends().get(id) ?: return Result.success()
        if (job.status != SendStatus.PENDING) return Result.success()

        val lateMs = System.currentTimeMillis() - job.sendAt
        val afterUnlock = inputData.getBoolean(SendScheduler.EXTRA_AFTER_UNLOCK, false)
        if (lateMs > if (afterUnlock) UNLOCK_LIMIT_MS else LATE_LIMIT_MS) {
            finish(job.id, SendStatus.ABORTED, L.t("Missed by ${lateMs / 60_000} min (phone off or asleep). Not sent late.", "פוספס ב-${lateMs / 60_000} דקות (הטלפון היה כבוי או רדום). לא נשלח באיחור."), job.recipientLabel)
            return Result.success()
        }
        app.db.sends().setStatus(job.id, SendStatus.RUNNING, L.t("Running", "בביצוע"))

        if (job.channel == SendChannel.SMS) {
            if (!SmsSender.hasPermission(app)) {
                finish(job.id, SendStatus.FAILED, L.t("SMS permission is off", "ההרשאה ל-SMS כבויה"), job.recipientLabel)
                return Result.success()
            }
            return try {
                SmsSender.send(app, job.id, job.recipient, job.text)
                app.db.sends().setStatus(job.id, SendStatus.RUNNING, L.t("Handed to SmsManager, waiting for radio result", "הועבר לשליחה, ממתין לאישור מהרשת"))
                Result.success()
            } catch (e: Exception) {
                finish(job.id, SendStatus.FAILED, L.t("SmsManager error: ${e.message}", "שגיאת שליחת SMS: ${e.message}"), job.recipientLabel)
                Result.success()
            }
        }

        val svc = AutomationService.instance
        if (svc == null) {
            finish(job.id, SendStatus.FAILED, L.t("Accessibility service is not enabled, so ${job.appLabel} could not be driven", "שירות הנגישות לא מופעל, ולכן אי אפשר היה להפעיל את ${job.appLabel}"), job.recipientLabel)
            return Result.success()
        }
        val outcome = withContext(Dispatchers.Main) {
            withTimeoutOrNull(60_000) { svc.performSend(job) }
        } ?: AutomationService.Outcome(false, true, L.t("Timed out waiting for ${job.appLabel}", "נגמר הזמן בהמתנה ל-${job.appLabel}"))
        if (outcome.waitUnlock) {
            app.db.sends().setStatus(job.id, SendStatus.WAITING_UNLOCK, outcome.note)
            Notifier.event(app, L.t("Unlock to send", "פתח את הנעילה כדי לשלוח"), "${job.recipientLabel}: ${job.text.take(60)}", "schedules")
            return Result.success()
        }
        finish(
            job.id,
            when {
                outcome.sent -> SendStatus.SENT
                outcome.aborted -> SendStatus.ABORTED
                else -> SendStatus.FAILED
            },
            outcome.note,
            job.recipientLabel,
        )
        return Result.success()
    }

    private suspend fun finish(id: Long, status: String, note: String, who: String) {
        val app = applicationContext as AutoAgentApp
        app.db.sends().setStatus(id, status, note)
        val ok = status == SendStatus.SENT
        app.log("send", "$status → $who: $note", ok)
        Notifier.event(
            app,
            if (ok) L.t("Scheduled message sent", "הודעה מתוזמנת נשלחה") else L.t("Scheduled message: ", "הודעה מתוזמנת: ") + SendStatus.label(status),
            "$who: $note",
            "schedules",
        )
        AgentStatus.refresh(app)
    }

    companion object {
        const val LATE_LIMIT_MS = 15 * 60_000L
        const val UNLOCK_LIMIT_MS = 60 * 60_000L
    }
}
