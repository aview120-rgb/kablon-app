package com.autoagent.app.receipts

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.autoagent.app.AutoAgentApp
import com.autoagent.app.data.Receipt
import com.autoagent.app.data.ReceiptSource
import com.autoagent.app.data.SecretBox
import com.autoagent.app.util.Notifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Date
import java.util.Properties
import javax.mail.Folder
import javax.mail.Message
import javax.mail.Multipart
import javax.mail.Part
import javax.mail.Session
import javax.mail.search.ComparisonTerm
import javax.mail.search.ReceivedDateTerm

/** Optional: reads receipt e-mails over IMAPS only when the user entered host, user and app password. */
object ImapFetcher {
    private val subjectWords = listOf("receipt", "invoice", "order", "payment", "קבלה", "חשבונית", "rechnung", "factura", "facture")

    data class Outcome(val added: Int, val scanned: Int)

    suspend fun fetch(app: AutoAgentApp, days: Int = 30): Outcome = withContext(Dispatchers.IO) {
        val s = app.settings.current
        val enc = app.settings.encryptedImapPassword()
        require(s.imapHost.isNotBlank() && s.imapUser.isNotBlank() && enc != null) { "E-mail is not configured" }
        val password = SecretBox.decrypt(enc) ?: error("Saved password could not be decrypted; enter it again")
        val props = Properties().apply {
            put("mail.store.protocol", "imaps")
            put("mail.imaps.host", s.imapHost)
            put("mail.imaps.port", s.imapPort.toString())
            put("mail.imaps.ssl.enable", "true")
            put("mail.imaps.ssl.checkserveridentity", "true")
            put("mail.imaps.connectiontimeout", "20000")
            put("mail.imaps.timeout", "30000")
        }
        val session = Session.getInstance(props)
        val store = session.getStore("imaps")
        store.connect(s.imapHost, s.imapPort, s.imapUser, password)
        var added = 0
        var scanned = 0
        try {
            val inbox = store.getFolder("INBOX")
            inbox.open(Folder.READ_ONLY)
            val since = Date(System.currentTimeMillis() - days * 86_400_000L)
            val messages = inbox.search(ReceivedDateTerm(ComparisonTerm.GE, since))
            for (m in messages.takeLast(300)) {
                val subject = m.subject.orEmpty()
                if (subjectWords.none { subject.contains(it, ignoreCase = true) }) continue
                scanned++
                val id = (m.getHeader("Message-ID")?.firstOrNull() ?: "${m.receivedDate?.time}-$subject")
                val ref = "email:$id"
                if (app.db.receipts().countRef(ref) > 0) continue
                val body = textOf(m)
                val parsed = ReceiptParser.parse("$subject\n$body")
                val from = (m.from?.firstOrNull()?.toString() ?: "").substringBefore('<').trim().trim('"')
                val receipt = Receipt(
                    merchant = parsed.merchant.ifBlank { from.ifBlank { subject.take(40) } },
                    dateText = parsed.dateText.ifBlank { m.receivedDate?.toString().orEmpty() },
                    dateMillis = parsed.dateMillis ?: m.receivedDate?.time,
                    total = parsed.total,
                    currency = parsed.currency,
                    source = ReceiptSource.EMAIL,
                    rawText = ("$subject\n$body").take(8000),
                    filePath = null,
                    sourceRef = ref,
                )
                if (app.db.receipts().insert(receipt) > 0) added++
            }
            inbox.close(false)
        } finally {
            store.close()
        }
        Outcome(added, scanned)
    }

    private fun textOf(p: Part): String = when {
        p.isMimeType("text/plain") -> p.content.toString()
        p.isMimeType("text/html") -> p.content.toString()
            .replace(Regex("(?is)<(script|style).*?</\\1>"), " ")
            .replace(Regex("(?i)<br\\s*/?>|</p>|</tr>|</div>"), "\n")
            .replace(Regex("<[^>]+>"), " ")
            .replace("&nbsp;", " ").replace("&amp;", "&")
            .replace(Regex("[ \\t]+"), " ")
        p.isMimeType("multipart/*") -> {
            val mp = p.content as Multipart
            val parts = (0 until mp.count).map { mp.getBodyPart(it) }
            val plain = parts.firstOrNull { it.isMimeType("text/plain") }
            if (plain != null) textOf(plain) else parts.joinToString("\n") { textOf(it) }
        }
        p is Message && p.isMimeType("message/rfc822") -> textOf(p.content as Part)
        else -> ""
    }
}

class ImapWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as AutoAgentApp
        return try {
            val out = ImapFetcher.fetch(app)
            app.log("imap", "E-mail scan: ${out.scanned} receipt-like, ${out.added} new", true)
            Notifier.event(app, "E-mail receipts", "Found ${out.added} new receipt(s) in ${out.scanned} matching e-mails", "receipts")
            Result.success()
        } catch (e: Exception) {
            app.log("imap", "E-mail scan failed: ${e.message}", false)
            Notifier.event(app, "E-mail receipts failed", e.message ?: e.javaClass.simpleName, "receipts")
            Result.failure()
        }
    }

    companion object {
        fun runNow(context: Context) {
            val req = OneTimeWorkRequestBuilder<ImapWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("imap-sync", ExistingWorkPolicy.REPLACE, req)
        }
    }
}
