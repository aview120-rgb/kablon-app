package com.autoagent.app.data

import androidx.room.ColumnInfo
import com.autoagent.app.util.L
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "alarms")
data class AlarmEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val triggerAt: Long,
    val label: String,
    val enabled: Boolean = true,
)

object SendChannel {
    const val SMS = "SMS"
    const val WHATSAPP = "WHATSAPP"
    const val APP = "APP"
}

object SendStatus {
    const val PENDING = "PENDING"
    const val RUNNING = "RUNNING"
    const val SENT = "SENT"
    const val FAILED = "FAILED"
    const val ABORTED = "ABORTED"
    const val CANCELLED = "CANCELLED"

    fun label(status: String): String = when (status) {
        PENDING -> L.t("Pending", "ממתין")
        RUNNING -> L.t("Running", "בביצוע")
        SENT -> L.t("Sent", "נשלח")
        FAILED -> L.t("Failed", "נכשל")
        ABORTED -> L.t("Aborted", "בוטל - המסך לא התאים")
        CANCELLED -> L.t("Cancelled", "בוטל")
        else -> status
    }
}

/** One message to exactly one recipient (contact or group). No bulk lists by design. */
@Entity(tableName = "scheduled_sends")
data class ScheduledSend(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val channel: String,
    val packageName: String,
    val appLabel: String,
    /** Phone number for SMS / WhatsApp direct; otherwise the exact contact or group name shown in the target app. */
    val recipient: String,
    val recipientLabel: String,
    val text: String,
    val sendAt: Long,
    val status: String = SendStatus.PENDING,
    val resultNote: String = "",
    val createdAt: Long = System.currentTimeMillis(),
)

object RuleIntent {
    const val WHO_IS_THIS = "WHO_IS_THIS"
    const val CALLBACK_TIME = "CALLBACK_TIME"
    const val TAKE_MESSAGE = "TAKE_MESSAGE"
    const val URGENT = "URGENT"
    const val SALES = "SALES"
    const val CUSTOM = "CUSTOM"
}

@Entity(tableName = "keyword_rules")
data class KeywordRule(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val intent: String,
    /** Comma separated keywords / phrases, matched case-insensitively. */
    val keywords: String,
    /** English reply. */
    val response: String,
    val priority: Int = 0,
    val enabled: Boolean = true,
    /** Hebrew reply (used when the app language is Hebrew). */
    @ColumnInfo(defaultValue = "")
    val responseHe: String = "",
)

@Entity(tableName = "call_records")
data class CallRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val number: String,
    val startedAt: Long,
    val endedAt: Long = 0,
    val transcript: String = "",
    val audioPath: String? = null,
    /** Explains what happened to audio capture (recorded, or why the OS blocked it). */
    val audioNote: String = "",
    val intents: String = "",
    val callbackDone: Boolean = false,
    val isTest: Boolean = false,
)

@Entity(tableName = "inbox", indices = [Index(value = ["dedupKey"], unique = true)])
data class InboxEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val appLabel: String,
    val sender: String,
    val text: String,
    val postedAt: Long,
    val dedupKey: String,
)

object ReceiptSource {
    const val IMAGE = "IMAGE"
    const val VOICE = "VOICE"
    const val EMAIL = "EMAIL"
    const val MANUAL = "MANUAL"
}

@Entity(tableName = "receipts", indices = [Index(value = ["sourceRef"], unique = true)])
data class Receipt(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val merchant: String,
    val dateText: String,
    val dateMillis: Long?,
    val total: Double?,
    val currency: String,
    val source: String,
    val rawText: String,
    val filePath: String?,
    /** Unique reference (file path or e-mail Message-ID) to avoid duplicates. */
    val sourceRef: String,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "action_log")
data class ActionLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val at: Long = System.currentTimeMillis(),
    val kind: String,
    val message: String,
    val ok: Boolean,
)
