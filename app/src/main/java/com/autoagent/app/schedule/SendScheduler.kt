package com.autoagent.app.schedule

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

object SendScheduler {
    const val EXTRA_SEND_ID = "send_id"
    /** Anti-spam guard: at most this many scheduled sends may be created per 24 hours. */
    const val DAILY_CAP = 25
    const val MAX_TEXT = 1000

    sealed interface CreateResult {
        data class Ok(val id: Long, val exact: Boolean) : CreateResult
        data class Rejected(val reason: String) : CreateResult
    }

    suspend fun create(context: Context, send: ScheduledSend): CreateResult {
        val app = context.applicationContext as AutoAgentApp
        if (send.text.isBlank()) return CreateResult.Rejected("Message text is empty")
        if (send.text.length > MAX_TEXT) return CreateResult.Rejected("Message is longer than $MAX_TEXT characters")
        if (send.recipient.isBlank()) return CreateResult.Rejected("Pick one recipient")
        if (send.recipient.contains(',') || send.recipient.contains(';')) {
            return CreateResult.Rejected("One recipient per schedule. Bulk lists are not supported.")
        }
        if (send.sendAt < System.currentTimeMillis() + 30_000) return CreateResult.Rejected("Pick a time at least 30 seconds ahead")
        val recent = app.db.sends().createdSince(System.currentTimeMillis() - 86_400_000L)
        if (recent >= DAILY_CAP) return CreateResult.Rejected("Daily limit of $DAILY_CAP scheduled messages reached")
        val id = app.db.sends().insert(send.copy(status = SendStatus.PENDING))
        val exact = arm(context, id, send.sendAt)
        app.log("schedule", "Scheduled ${send.channel} to ${send.recipientLabel} in ${send.appLabel}", true)
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
        app.db.sends().setStatus(send.id, SendStatus.CANCELLED, "Cancelled by you")
        app.log("schedule", "Cancelled send to ${send.recipientLabel}", true)
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

    fun enqueueNow(context: Context, id: Long) {
        val builder = OneTimeWorkRequestBuilder<SendWorker>().setInputData(workDataOf(EXTRA_SEND_ID to id))
        if (Build.VERSION.SDK_INT >= 31) builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        WorkManager.getInstance(context).enqueueUniqueWork("send-$id", ExistingWorkPolicy.KEEP, builder.build())
    }
}
