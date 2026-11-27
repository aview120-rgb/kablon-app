package com.autoagent.app.schedule

import com.autoagent.app.util.L
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.autoagent.app.AutoAgentApp
import com.autoagent.app.R
import com.autoagent.app.access.AccessibilityHealth
import com.autoagent.app.access.AutomationService
import com.autoagent.app.access.ScreenActions
import com.autoagent.app.access.SerialActionQueue
import com.autoagent.app.sync.RemoteTaskExecutor
import com.autoagent.app.data.SendChannel
import com.autoagent.app.data.SendStatus
import com.autoagent.app.service.AgentStatus
import com.autoagent.app.util.Notifier

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
        val limit = if (afterUnlock) UNLOCK_LIMIT_MS else job.remoteTaskId?.let { RemoteTaskExecutor.lateLimitMs(app, it) } ?: LATE_LIMIT_MS
        if (lateMs > limit) {
            finish(job.id, SendStatus.ABORTED, L.t("Missed by ${lateMs / 60_000} min (phone off or asleep). Not sent late.", "פוספס ב-${lateMs / 60_000} דקות (הטלפון היה כבוי או רדום). לא נשלח באיחור."), job.recipientLabel)
            return Result.success()
        }
        app.db.sends().setStatus(job.id, SendStatus.RUNNING, L.t("Running", "בביצוע"))

        if (job.channel == SendChannel.SMS && !job.autoSend) {
            // Prepare only: a notification opens your SMS app with the text filled in; you press Send.
            val compose = android.content.Intent(android.content.Intent.ACTION_SENDTO, android.net.Uri.parse("smsto:" + android.net.Uri.encode(job.recipient)))
                .putExtra("sms_body", job.text).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            val pi = android.app.PendingIntent.getActivity(app, (200_000 + job.id).toInt(), compose,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
            if (Notifier.canPost(app)) {
                val n = NotificationCompat.Builder(app, Notifier.CH_EVENTS)
                    .setSmallIcon(R.drawable.ic_stat_agent)
                    .setContentTitle(L.t("SMS ready for ${job.recipientLabel}", "SMS מוכן ל${job.recipientLabel}"))
                    .setContentText(L.t("Tap to open it and press Send", "הקש כדי לפתוח וללחוץ שלח"))
                    .setStyle(NotificationCompat.BigTextStyle().bigText(job.text))
                    .setContentIntent(pi).setAutoCancel(true).build()
                try { androidx.core.app.NotificationManagerCompat.from(app).notify((300_000 + job.id).toInt(), n) } catch (_: SecurityException) {}
            }
            finish(job.id, SendStatus.PREPARED, L.t("SMS prepared; waiting for you to press Send", "ה-SMS הוכן; ממתין שתלחץ שלח"), job.recipientLabel)
            return Result.success()
        }

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

        // Recovery instead of failure: give the service a moment to (re)connect, try to restart it, then wait for it.
        if (AccessibilityHealth.awaitReady(app, 15_000) == null) {
            val how = AccessibilityHealth.recover(app)
            if (AccessibilityHealth.awaitReady(app, 10_000) == null) {
                app.db.sends().setStatus(job.id, SendStatus.WAITING_SERVICE,
                    L.t("Waiting for the accessibility service (sent as soon as it is back, up to 60 min late). $how",
                        "ממתין לשירות הנגישות (יישלח ברגע שיחזור, עד 60 דקות באיחור). $how"))
                Notifier.event(app, L.t("Message waiting for accessibility", "הודעה ממתינה לשירות הנגישות"), "${job.recipientLabel}: ${job.text.take(60)}", "permissions")
                AgentStatus.refresh(app)
                return Result.success()
            }
        }
        val label = L.t("${job.appLabel} → ${job.recipientLabel}", "${job.appLabel} ← ${job.recipientLabel}")
        val outcome = when (val r = ScreenActions.runOnService(app, label, timeoutMs = 75_000) { svc -> svc.performSend(job) }) {
            is SerialActionQueue.Result.Done -> r.value
            is SerialActionQueue.Result.TimedOut -> AutomationService.Outcome(false, true, L.t("Timed out waiting for ${job.appLabel}", "נגמר הזמן בהמתנה ל-${job.appLabel}"))
            is SerialActionQueue.Result.Busy -> AutomationService.Outcome(false, true, L.t("Screen was busy: ${r.reason}", "המסך היה תפוס: ${r.reason}"))
            is SerialActionQueue.Result.Failed -> AutomationService.Outcome(false, false, L.t("Error: ${r.error.message}", "שגיאה: ${r.error.message}"))
        }
        if (outcome.prepared) {
            finish(job.id, SendStatus.PREPARED, outcome.note, job.recipientLabel)
            return Result.success()
        }
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
        finishSend(applicationContext, id, status, note, who)
    }

    companion object {
        /** Final status of a send: log, notification, and (for server tasks) the outcome queued for the server. */
        suspend fun finishSend(context: Context, id: Long, status: String, note: String, who: String) {
            val app = context.applicationContext as AutoAgentApp
            app.db.sends().setStatus(id, status, note)
            val ok = status == SendStatus.SENT || status == SendStatus.PREPARED
            app.log("send", "${SendStatus.label(status)} ← $who: $note", ok)
            Notifier.event(
                app,
                when (status) {
                    SendStatus.SENT -> L.t("Scheduled message sent", "הודעה מתוזמנת נשלחה")
                    SendStatus.PREPARED -> L.t("Message ready — press Send", "ההודעה מוכנה — לחץ שלח")
                    else -> L.t("Scheduled message: ", "הודעה מתוזמנת: ") + SendStatus.label(status)
                },
                "$who: $note",
                "schedules",
            )
            app.db.sends().get(id)?.remoteTaskId?.let { taskId -> RemoteTaskExecutor.onSendFinished(app, taskId, status, note) }
            AgentStatus.refresh(app)
        }

        const val LATE_LIMIT_MS = 15 * 60_000L
        const val UNLOCK_LIMIT_MS = 60 * 60_000L
    }
}
