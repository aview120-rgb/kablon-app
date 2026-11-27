package com.autoagent.app.sync

import android.content.Context
import com.autoagent.app.AutoAgentApp
import com.autoagent.app.data.OutboxItem
import com.autoagent.app.data.OutboxKind
import org.json.JSONObject

/** Collects data while offline; [SyncEngine] uploads it on the next successful connection. */
object Outbox {
    private const val MAX_ITEMS = 5_000

    suspend fun add(context: Context, kind: String, data: JSONObject, at: Long = System.currentTimeMillis()) {
        val app = context.applicationContext as AutoAgentApp
        if (!app.settings.current.syncEnabled) return
        app.db.outbox().insert(OutboxItem(kind = kind, dataJson = data.toString(), at = at))
        app.db.outbox().trim(MAX_ITEMS)
        SyncScheduler.syncNow(app, "outbox")
    }

    /** Inbox upload is opt-in (Settings → Sync). */
    suspend fun addInbox(context: Context, app: String, sender: String, text: String, at: Long) {
        val a = context.applicationContext as AutoAgentApp
        if (!a.settings.current.uploadInbox) return
        add(context, OutboxKind.INBOX, JSONObject().put("app", app).put("sender", sender).put("text", text.take(2000)), at)
    }

    /** A task created on the phone (possibly offline) for the server to process. */
    suspend fun addServerTask(context: Context, task: JSONObject) = add(context, OutboxKind.TASK, task)
}
