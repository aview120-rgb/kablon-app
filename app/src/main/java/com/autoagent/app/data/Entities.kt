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
    const val WAITING_UNLOCK = "WAITING_UNLOCK"
    /** The accessibility service was not connected; resumes by itself when it comes back. */
    const val WAITING_SERVICE = "WAITING_SERVICE"
    /** "Prepare only": the chat is open with the text typed in; you press Send yourself. */
    const val PREPARED = "PREPARED"

    fun label(status: String): String = when (status) {
        PENDING -> L.t("Pending", "ממתין")
        RUNNING -> L.t("Running", "בביצוע")
        SENT -> L.t("Sent", "נשלח")
        FAILED -> L.t("Failed", "נכשל")
        ABORTED -> L.t("Aborted", "בוטל - המסך לא התאים")
        CANCELLED -> L.t("Cancelled", "בוטל")
        WAITING_UNLOCK -> L.t("Waiting for unlock", "ממתין לפתיחת המסך")
        WAITING_SERVICE -> L.t("Waiting for accessibility service", "ממתין לשירות הנגישות")
        PREPARED -> L.t("Prepared — press Send", "מוכנה — לחץ שלח")
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
    /** Server task this send came from (null = created on the phone). */
    @ColumnInfo(defaultValue = "NULL")
    val remoteTaskId: String? = null,
    /** false = only prepare the message in the app and let you press Send. */
    @ColumnInfo(defaultValue = "1")
    val autoSend: Boolean = true,
)

object RuleIntent {
    const val WHO_IS_THIS = "WHO_IS_THIS"
    const val CALLBACK_TIME = "CALLBACK_TIME"
    const val TAKE_MESSAGE = "TAKE_MESSAGE"
    const val URGENT = "URGENT"
    const val SALES = "SALES"
    const val WANT_OWNER = "WANT_OWNER"
    const val DELIVERY = "DELIVERY"
    const val APPOINTMENT = "APPOINTMENT"
    const val WRONG_NUMBER = "WRONG_NUMBER"
    const val GOODBYE = "GOODBYE"
    const val HELLO = "HELLO"
    const val CUSTOM = "CUSTOM"

    /** After these replies the assistant listens for the caller's message. */
    val EXPECTS_MESSAGE = setOf(TAKE_MESSAGE, CALLBACK_TIME, URGENT, DELIVERY, APPOINTMENT, WANT_OWNER, "UNKNOWN")

    /** These end the call politely. */
    val ENDS_CALL = setOf(GOODBYE, WRONG_NUMBER, SALES)
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

/** Local state of a task the server prepared. Kept until its outcome reached the server. */
object RemoteState {
    const val RECEIVED = "RECEIVED"
    const val SCHEDULED = "SCHEDULED"
    const val RUNNING = "RUNNING"
    const val DONE = "DONE"
    const val FAILED = "FAILED"
    const val EXPIRED = "EXPIRED"
    const val CANCELLED = "CANCELLED"

    val FINAL = setOf(DONE, FAILED, EXPIRED, CANCELLED)

    fun label(state: String): String = when (state) {
        RECEIVED -> L.t("Received", "התקבלה")
        SCHEDULED -> L.t("Scheduled on phone", "מתוזמנת בטלפון")
        RUNNING -> L.t("Running", "בביצוע")
        DONE -> L.t("Done", "בוצעה")
        FAILED -> L.t("Failed", "נכשלה")
        EXPIRED -> L.t("Expired", "פג תוקף")
        CANCELLED -> L.t("Cancelled", "בוטלה")
        else -> state
    }
}

@Entity(tableName = "remote_tasks")
data class RemoteTask(
    /** Server id. */
    @PrimaryKey val id: String,
    val type: String,
    val title: String,
    /** The server's prepared result as JSON. */
    val resultJson: String,
    /** When to run, already converted to phone time. */
    val executeAt: Long,
    val maxLateMinutes: Int,
    val state: String = RemoteState.RECEIVED,
    val note: String = "",
    /** True once the server confirmed it received the final outcome. */
    val reported: Boolean = false,
    /** True once the server was told the phone stored it. */
    val ackSent: Boolean = false,
    val receivedAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

object OutboxKind {
    const val INBOX = "inbox"
    const val LOG = "log"
    const val TASK = "task"
}

/** Something the phone collected while offline, uploaded on the next sync (deleted after the server accepted it). */
@Entity(tableName = "outbox", indices = [Index(value = ["clientId"], unique = true)])
data class OutboxItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val clientId: String = java.util.UUID.randomUUID().toString(),
    val kind: String,
    val dataJson: String,
    val at: Long = System.currentTimeMillis(),
    val attempts: Int = 0,
)
