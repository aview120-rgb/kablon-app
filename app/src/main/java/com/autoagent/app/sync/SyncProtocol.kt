package com.autoagent.app.sync

import org.json.JSONArray
import org.json.JSONObject

/** Wire format of POST /api/v1/sync (see server/README.md). Plain data + org.json, covered by unit tests. */
data class TaskResult(val taskId: String, val status: String, val note: String, val at: Long)

data class Upload(val clientId: String, val kind: String, val dataJson: String, val at: Long)

data class SyncRequest(
    val deviceId: String,
    val deviceName: String,
    val appVersion: String,
    val now: Long,
    val status: Map<String, Any?>,
    val received: List<String>,
    val results: List<TaskResult>,
    val uploads: List<Upload>,
)

data class SyncTask(
    val id: String,
    val type: String,
    val title: String,
    val runAt: Long,
    val resultJson: String,
    val occurrence: Int,
)

data class SyncResponse(
    val serverTime: Long,
    val tasks: List<SyncTask>,
    val cancelled: List<String>,
    val acceptedUploads: List<String>,
    val acceptedResults: List<String>,
    val hasMore: Boolean,
    val nextPollMs: Long,
)

object SyncProtocol {
    fun encode(r: SyncRequest): String = JSONObject().apply {
        put("deviceId", r.deviceId)
        put("deviceName", r.deviceName)
        put("appVersion", r.appVersion)
        put("now", r.now)
        put("status", JSONObject().apply { r.status.forEach { (k, v) -> put(k, v ?: JSONObject.NULL) } })
        put("received", JSONArray(r.received))
        put("results", JSONArray().apply {
            r.results.forEach { put(JSONObject().put("taskId", it.taskId).put("status", it.status).put("note", it.note).put("at", it.at)) }
        })
        put("uploads", JSONArray().apply {
            r.uploads.forEach {
                val data: Any = runCatching { JSONObject(it.dataJson) }.getOrElse { _ -> it.dataJson }
                put(JSONObject().put("clientId", it.clientId).put("kind", it.kind).put("at", it.at).put("data", data))
            }
        })
    }.toString()

    fun decode(json: String): SyncResponse {
        val o = JSONObject(json)
        val tasks = o.optJSONArray("tasks") ?: JSONArray()
        return SyncResponse(
            serverTime = o.optLong("serverTime", 0),
            tasks = (0 until tasks.length()).map { i ->
                val t = tasks.getJSONObject(i)
                SyncTask(
                    id = t.getString("id"),
                    type = t.optString("type"),
                    title = t.optString("title"),
                    runAt = t.optLong("runAt"),
                    resultJson = t.optJSONObject("result")?.toString() ?: "{}",
                    occurrence = t.optInt("occurrence", 1),
                )
            },
            cancelled = strings(o.optJSONArray("cancelled")),
            acceptedUploads = strings(o.optJSONArray("acceptedUploads")),
            acceptedResults = strings(o.optJSONArray("acceptedResults")),
            hasMore = o.optBoolean("hasMore", false),
            nextPollMs = o.optLong("nextPollMs", 25_000),
        )
    }

    /** Server error body {"error": "..."} → message, or null. */
    fun errorMessage(body: String?): String? =
        body?.let { runCatching { JSONObject(it).optString("error").takeIf { s -> s.isNotBlank() } }.getOrNull() }

    private fun strings(a: JSONArray?): List<String> = if (a == null) emptyList() else (0 until a.length()).map { a.getString(it) }

    /** Local outcome → server status word. */
    fun remoteStatus(localState: String): String = when (localState) {
        com.autoagent.app.data.RemoteState.DONE -> "done"
        com.autoagent.app.data.RemoteState.EXPIRED -> "expired"
        else -> "failed"
    }
}

/** Decides what to do with a prepared task when it reaches the phone. */
object RemotePlanner {
    enum class Decision { RUN_NOW, SCHEDULE, EXPIRE }

    /** Server time → phone time, correcting for clock difference between the two. */
    fun toLocalTime(serverRunAt: Long, serverNow: Long, localNow: Long): Long =
        if (serverNow <= 0) serverRunAt else serverRunAt + (localNow - serverNow)

    fun decide(executeAt: Long, now: Long, maxLateMinutes: Int): Decision = when {
        executeAt > now + 1_000 -> Decision.SCHEDULE
        now - executeAt <= maxLateMinutes * 60_000L -> Decision.RUN_NOW
        else -> Decision.EXPIRE
    }
}
