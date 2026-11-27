package com.autoagent.app.sync

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.autoagent.app.AutoAgentApp
import com.autoagent.app.data.RemoteState
import com.autoagent.app.data.RemoteTask
import com.autoagent.app.data.ScheduledSend
import com.autoagent.app.data.SendChannel
import com.autoagent.app.data.SendStatus
import com.autoagent.app.schedule.SendScheduler
import com.autoagent.app.schedule.SmsSender
import com.autoagent.app.access.AccessibilityHealth
import com.autoagent.app.util.L
import com.autoagent.app.util.Notifier
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Turns tasks the server prepared into local actions. Stored first (idempotent by server id), then executed now,
 * scheduled locally (so they run on time even if the phone goes offline again), or marked expired.
 * Outcomes are kept until the server confirmed them.
 */
object RemoteTaskExecutor {
    const val EXTRA_TASK_ID = "remote_task_id"

    /** Returns true if the task was new. */
    suspend fun accept(context: Context, t: SyncTask, serverNow: Long, localNow: Long): Boolean {
        val app = context.applicationContext as AutoAgentApp
        val result = runCatching { JSONObject(t.resultJson) }.getOrDefault(JSONObject())
        val serverExecuteAt = result.optLong("executeAt", t.runAt)
        val task = RemoteTask(
            id = t.id,
            type = t.type,
            title = t.title.ifBlank { t.type },
            resultJson = t.resultJson,
            executeAt = RemotePlanner.toLocalTime(serverExecuteAt, serverNow, localNow),
            maxLateMinutes = result.optInt("maxLateMinutes", 15),
        )
        if (app.db.remoteTasks().insertIfNew(task) == -1L) return false
        app.log("sync", L.t("Server task received: ${task.title}", "התקבלה משימה מהשרת: ${task.title}"), true)
        dispatch(app, task)
        return true
    }

    private suspend fun dispatch(app: AutoAgentApp, task: RemoteTask) {
        val decision = RemotePlanner.decide(task.executeAt, System.currentTimeMillis(), task.maxLateMinutes)
        if (decision == RemotePlanner.Decision.EXPIRE) {
            finish(app, task.id, RemoteState.EXPIRED, L.t("Arrived too late (phone was offline); not run", "הגיעה מאוחר מדי (הטלפון לא היה מקוון); לא בוצעה"))
            return
        }
        when (task.type) {
            "send_message", "prepare_message" -> scheduleSend(app, task)
            "reminder", "digest", "fetch_url" -> {
                if (decision == RemotePlanner.Decision.RUN_NOW) runNotification(app, task.id)
                else {
                    app.db.remoteTasks().setState(task.id, RemoteState.SCHEDULED, L.t("Scheduled on the phone", "מתוזמנת בטלפון"))
                    val delay = (task.executeAt - System.currentTimeMillis()).coerceAtLeast(0)
                    WorkManager.getInstance(app).enqueueUniqueWork(
                        "remote-${task.id}", ExistingWorkPolicy.KEEP,
                        OneTimeWorkRequestBuilder<RemoteTaskWorker>()
                            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                            .setInputData(workDataOf(EXTRA_TASK_ID to task.id))
                            .build(),
                    )
                }
            }
            else -> finish(app, task.id, RemoteState.FAILED, L.t("Unsupported task type: ${task.type}", "סוג משימה לא נתמך: ${task.type}"))
        }
    }

    private suspend fun scheduleSend(app: AutoAgentApp, task: RemoteTask) {
        val r = JSONObject(task.resultJson)
        val channel = r.optString("channel", SendChannel.WHATSAPP)
        // Safety: the server may only press Send if you allowed it on the phone. Otherwise the message is prepared.
        val autoSend = r.optBoolean("autoSend", false) && app.settings.current.remoteAutoSend
        val pkg = r.optString("packageName").ifBlank { if (channel == SendChannel.WHATSAPP) "com.whatsapp" else "" }
        val permissionNote = when {
            channel == SendChannel.SMS && autoSend && !SmsSender.hasPermission(app) -> L.t("SMS permission is off", "ההרשאה ל-SMS כבויה")
            channel != SendChannel.SMS && pkg.isBlank() -> L.t("No app was given for this message", "לא צוינה אפליקציה להודעה")
            channel != SendChannel.SMS && app.packageManager.getLaunchIntentForPackage(pkg) == null -> L.t("$pkg is not installed", "$pkg לא מותקנת")
            else -> null
        }
        if (permissionNote != null) {
            finish(app, task.id, RemoteState.FAILED, permissionNote)
            return
        }
        val send = ScheduledSend(
            channel = channel,
            packageName = if (channel == SendChannel.SMS) "sms" else pkg,
            appLabel = r.optString("appLabel", if (channel == SendChannel.SMS) "SMS" else pkg),
            recipient = r.optString("recipient"),
            recipientLabel = r.optString("recipientLabel", r.optString("recipient")),
            text = r.optString("text"),
            sendAt = task.executeAt,
            remoteTaskId = task.id,
            autoSend = autoSend,
        )
        when (val res = SendScheduler.create(app, send, fromServer = true)) {
            is SendScheduler.CreateResult.Ok -> {
                val note = buildString {
                    append(if (autoSend) L.t("Scheduled to send", "מתוזמנת לשליחה") else L.t("Scheduled to prepare (you press Send)", "מתוזמנת להכנה (אתה לוחץ שלח)"))
                    if (channel != SendChannel.SMS && !AccessibilityHealth.refresh(app).ready) append(L.t("; accessibility service is not active yet", "; שירות הנגישות עדיין לא פעיל"))
                }
                app.db.remoteTasks().setState(task.id, RemoteState.SCHEDULED, note)
            }
            is SendScheduler.CreateResult.Rejected -> finish(app, task.id, RemoteState.FAILED, res.reason)
        }
    }

    /** Reminder / digest / web check: shows the prepared result as a notification. */
    suspend fun runNotification(context: Context, id: String) {
        val app = context.applicationContext as AutoAgentApp
        val task = app.db.remoteTasks().get(id) ?: return
        if (task.state in RemoteState.FINAL) return
        if (RemotePlanner.decide(task.executeAt, System.currentTimeMillis(), task.maxLateMinutes) == RemotePlanner.Decision.EXPIRE) {
            finish(app, id, RemoteState.EXPIRED, L.t("Missed its time (phone was off); not shown", "פספסה את הזמן (הטלפון היה כבוי); לא הוצגה"))
            return
        }
        val r = runCatching { JSONObject(task.resultJson) }.getOrDefault(JSONObject())
        val title = r.optString("title", task.title)
        val text = r.optString("text", "")
        Notifier.event(app, title, text, "sync")
        finish(app, id, RemoteState.DONE, L.t("Shown as a notification", "הוצגה כהתראה"))
    }

    suspend fun onSendFinished(context: Context, taskId: String, sendStatus: String, note: String) {
        val state = when (sendStatus) {
            SendStatus.SENT, SendStatus.PREPARED -> RemoteState.DONE
            SendStatus.RUNNING, SendStatus.PENDING, SendStatus.WAITING_UNLOCK, SendStatus.WAITING_SERVICE -> return
            else -> RemoteState.FAILED
        }
        finish(context.applicationContext as AutoAgentApp, taskId, state, "${SendStatus.label(sendStatus)}: $note")
    }

    /** Server cancelled the task: drop the local copy (and its scheduled send) if it has not run yet. */
    suspend fun cancel(context: Context, id: String) {
        val app = context.applicationContext as AutoAgentApp
        val task = app.db.remoteTasks().get(id) ?: return
        if (task.state in RemoteState.FINAL) return
        WorkManager.getInstance(app).cancelUniqueWork("remote-$id")
        app.db.sends().byRemoteTask(id)?.let { if (it.status == SendStatus.PENDING || it.status == SendStatus.WAITING_SERVICE || it.status == SendStatus.WAITING_UNLOCK) SendScheduler.cancel(app, it) }
        app.db.remoteTasks().update(task.copy(state = RemoteState.CANCELLED, note = L.t("Cancelled on the server", "בוטלה בשרת"), reported = true, updatedAt = System.currentTimeMillis()))
        app.log("sync", L.t("Server cancelled: ${task.title}", "השרת ביטל: ${task.title}"), true)
    }

    suspend fun lateLimitMs(context: Context, id: String): Long? =
        (context.applicationContext as AutoAgentApp).db.remoteTasks().get(id)?.maxLateMinutes?.let { it * 60_000L }

    /** After a crash between storing and dispatching, tasks stay RECEIVED; dispatch them again. */
    suspend fun resumeOpen(context: Context) {
        val app = context.applicationContext as AutoAgentApp
        for (t in app.db.remoteTasks().open()) if (t.state == RemoteState.RECEIVED) dispatch(app, t)
    }

    private suspend fun finish(app: AutoAgentApp, id: String, state: String, note: String) {
        app.db.remoteTasks().setState(id, state, note)
        app.log("sync", "${RemoteState.label(state)}: $note", state == RemoteState.DONE)
        // The outcome is stored; it reaches the server now or whenever the phone is next online.
        SyncScheduler.syncNow(app, "result")
    }
}
