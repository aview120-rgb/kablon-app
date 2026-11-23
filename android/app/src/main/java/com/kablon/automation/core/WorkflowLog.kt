package com.kablon.automation.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class StepStatus(val icon: String) {
    START("▶"), OK("✅"), FAIL("❌"), MANUAL("✋"), SKIP("⏭"), INFO("ℹ"), STOP("⏹")
}

data class LogEntry(
    val time: Long,
    val workflow: String,
    val step: String,
    val status: StepStatus,
    val detail: String,
    val model: String?,
) {
    fun format(): String {
        val t = SimpleDateFormat("dd/MM HH:mm:ss", Locale.getDefault()).format(Date(time))
        val m = if (model.isNullOrBlank()) "" else " [מודל: $model]"
        val d = if (detail.isBlank()) "" else "\n    $detail"
        return "${status.icon} $t | $workflow › $step$m$d"
    }
}

/** יומן שלבים עמיד (JSON lines) – מה עבד, איפה נעצר ואיזה מודל השתתף. */
object WorkflowLog {
    private const val MAX_BYTES = 2L * 1024 * 1024
    private var file: File? = null
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()

    fun init(context: Context) {
        file = File(context.filesDir, "workflow_log.jsonl")
    }

    @Synchronized
    fun add(workflow: String, step: String, status: StepStatus, detail: String = "", model: String? = null) {
        val f = file ?: return
        try {
            if (f.exists() && f.length() > MAX_BYTES) {
                val keep = f.readLines().takeLast(2000)
                f.writeText(keep.joinToString("\n", postfix = "\n"))
            }
            val o = JSONObject()
                .put("t", System.currentTimeMillis())
                .put("w", workflow)
                .put("s", step)
                .put("st", status.name)
                .put("d", detail.take(4000))
                .put("m", model ?: "")
            f.appendText(o.toString() + "\n")
        } catch (_: Exception) {
        }
        _version.value = _version.value + 1
    }

    @Synchronized
    fun readAll(): List<LogEntry> {
        val f = file ?: return emptyList()
        if (!f.exists()) return emptyList()
        return f.readLines().mapNotNull { line ->
            try {
                val o = JSONObject(line)
                LogEntry(
                    o.getLong("t"), o.optString("w"), o.optString("s"),
                    runCatching { StepStatus.valueOf(o.optString("st")) }.getOrDefault(StepStatus.INFO),
                    o.optString("d"), o.optString("m").ifBlank { null },
                )
            } catch (_: Exception) {
                null
            }
        }
    }

    @Synchronized
    fun clear() {
        file?.delete()
        _version.value = _version.value + 1
    }
}
