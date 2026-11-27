package com.autoagent.app.schedule

import com.autoagent.app.util.L
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.autoagent.app.AutoAgentApp
import com.autoagent.app.alarm.AlarmScheduler
import com.autoagent.app.data.ScheduledSend
import com.autoagent.app.data.SendStatus
import com.autoagent.app.service.AgentStatus
import kotlinx.coroutines.launch

object SendScheduler {
    const val EXTRA_SEND_ID = "send_id"
    /** Anti-spam guard: at most this many scheduled sends may be created per 24 hours. */
    const val DAILY_CAP = 25
    const val MAX_TEXT = 1000

    sealed interface CreateResult {
        data class Ok(val id: Long, val exact: Boolean) : CreateResult
        data class Rejected(val reason: String) : CreateResult
    }

    suspend fun create(context: Context, send: ScheduledSend): CreateResult = create(context, send, fromServer = false)

    /**
     * [fromServer]: a task the server prepared. It may already be due (the phone was offline), so the
     * "30 s ahead" rule does not apply; due sends start immediately. All other guards stay.
     */
    suspend fun create(context: Context, send: ScheduledSend, fromServer: Boolean): CreateResult {
        val app = context.applicationContext as AutoAgentApp
        if (send.text.isBlank()) return CreateResult.Rejected(L.t("Message text is empty", "ההודעה ריקה"))
        if (send.text.length > MAX_TEXT) return CreateResult.Rejected(L.t("Message is longer than $MAX_TEXT characters", "ההודעה ארוכה מ-$MAX_TEXT תווים"))
        if (send.recipient.isBlank()) return CreateResult.Rejected(L.t("Pick one recipient", "צריך לבחור נמען אחד"))
        if (send.recipient.contains(',') || send.recipient.contains(';')) {
            return CreateResult.Rejected(L.t("One recipient per schedule. Bulk lists are not supported.", "נמען אחד לכל תזמון. אין תמיכה ברשימות תפוצה."))
        }
        if (!fromServer && send.sendAt < System.currentTimeMillis() + 30_000) return CreateResult.Rejected(L.t("Pick a time at least 30 seconds ahead", "צריך לבחור זמן של לפחות 30 שניות מעכשיו"))
        val recent = app.db.sends().createdSince(System.currentTimeMillis() - 86_400_000L)
        if (recent >= DAILY_CAP) return CreateResult.Rejected(L.t("Daily limit of $DAILY_CAP scheduled messages reached", "הגעת למגבלה היומית של $DAILY_CAP הודעות מתוזמנות"))
        val id = app.db.sends().insert(send.copy(status = SendStatus.PENDING))
        val exact = if (send.sendAt <= System.currentTimeMillis()) { enqueueNow(context, id); true } else arm(context, id, send.sendAt)
        app.log("schedule", L.t("Scheduled ${send.channel} to ${send.recipientLabel} in ${send.appLabel}", "תוזמנה הודעת ${send.channel} ל${send.recipientLabel} ב-${send.appLabel}") +
            if (fromServer) L.t(" (from server)", " (מהשרת)") else "", true)
        AgentStatus.refresh(context)
        return CreateResult.Ok(id, exact)
    }

    private fun pending(context: Context, id: Long): PendingIntent =
        PendingIntent.getBroadcast(
            context, (100_000 + id).toInt(),
            Intent(context, SendReceiver::class.java).setAction(SendReceiver.ACTION_SEND).putExtra(EXTRA_SEND_ID, id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /** Exact alarm if permitted, otherwise an inexact allow-while-idle alarm. Returns true when exact. */
    fun arm(context: Context, id: Long, at: Long): Boolean {
        val am = context.getSystemService(AlarmManager::class.java)
        val pi = pending(context, id)
        return if (AlarmScheduler.canScheduleExact(context)) {
            try {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
                true
            } catch (_: SecurityException) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi); false
            }
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            false
        }
    }

    suspend fun cancel(context: Context, send: ScheduledSend) {
        val app = context.applicationContext as AutoAgentApp
        context.getSystemService(AlarmManager::class.java).cancel(pending(context, send.id))
        androidx.work.WorkManager.getInstance(context).cancelUniqueWork("send-${send.id}")
        app.db.sends().setStatus(send.id, SendStatus.CANCELLED, L.t("Cancelled by you", "בוטל על ידך"))
        app.log("schedule", L.t("Cancelled send to ${send.recipientLabel}", "בוטלה שליחה ל${send.recipientLabel}"), true)
        AgentStatus.refresh(context)
    }

    suspend fun rearmAll(context: Context): Int {
        val app = context.applicationContext as AutoAgentApp
        var n = 0
        for (s in app.db.sends().pending()) {
            if (s.sendAt <= System.currentTimeMillis()) enqueueNow(context, s.id) else arm(context, s.id, s.sendAt)
            n++
        }
        return n
    }

    const val EXTRA_AFTER_UNLOCK = "after_unlock"

    /** Called when the phone is unlocked: resumes sends that were waiting for it (sent up to 60 min late). */
    fun resumeWaitingUnlock(context: Context) {
        val app = context.applicationContext as AutoAgentApp
        app.appScope.launch {
            for (s in app.db.sends().byStatus(SendStatus.WAITING_UNLOCK)) {
                app.db.sends().setStatus(s.id, SendStatus.PENDING, s.resultNote)
                enqueueNow(context, s.id, afterUnlock = true)
            }
        }
    }

    /** Called when the accessibility service (re)connects: sends that waited for it go out now (up to 60 min late). */
    fun resumeWaitingService(context: Context) {
        val app = context.applicationContext as AutoAgentApp
        app.appScope.launch {
            for (s in app.db.sends().byStatus(SendStatus.WAITING_SERVICE)) {
                app.db.sends().setStatus(s.id, SendStatus.PENDING, s.resultNote)
                enqueueNow(context, s.id, afterUnlock = true)
            }
        }
    }

    fun enqueueNow(context: Context, id: Long, afterUnlock: Boolean = false) {
        val builder = OneTimeWorkRequestBuilder<SendWorker>().setInputData(workDataOf(EXTRA_SEND_ID to id, EXTRA_AFTER_UNLOCK to afterUnlock))
        if (Build.VERSION.SDK_INT >= 31) builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        WorkManager.getInstance(context).enqueueUniqueWork("send-$id", if (afterUnlock) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, builder.build())
    }
}
