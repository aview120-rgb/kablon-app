package com.autoagent.app.receipts

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import com.autoagent.app.AutoAgentApp
import com.autoagent.app.data.Receipt
import com.autoagent.app.data.ReceiptSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Copies shared files into app storage, runs on-device OCR / speech-to-text, parses and saves to Room. */
object ReceiptIngest {
    data class Outcome(val receipt: Receipt?, val message: String)

    suspend fun copyToStorage(context: Context, uri: Uri, mime: String?): File = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, "receipts").apply { mkdirs() }
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime ?: context.contentResolver.getType(uri))
            ?: if (mime?.startsWith("audio") == true) "m4a" else "jpg"
        val out = File(dir, "r_${System.currentTimeMillis()}.$ext")
        context.contentResolver.openInputStream(uri)?.use { input -> out.outputStream().use { input.copyTo(it) } }
            ?: error("Could not read the shared file")
        out
    }

    suspend fun ingestFile(context: Context, file: File, isAudio: Boolean): Outcome {
        val app = context.applicationContext as AutoAgentApp
        val uri = Uri.fromFile(file)
        val text = if (isAudio) {
            when (val r = AudioTranscriber.transcribe(context, uri)) {
                is AudioTranscriber.Result.Text -> r.text
                is AudioTranscriber.Result.Unsupported -> return Outcome(null, r.reason + " The audio file was kept in app storage.")
            }
        } else {
            try {
                OcrEngine.recognize(context, uri)
            } catch (e: Exception) {
                return Outcome(null, "OCR failed: ${e.message}")
            }
        }
        if (text.isBlank()) return Outcome(null, "No text found in the ${if (isAudio) "voice note" else "image"}")
        return save(app, text, if (isAudio) ReceiptSource.VOICE else ReceiptSource.IMAGE, file.absolutePath, "file:${file.absolutePath}")
    }

    suspend fun ingestDictation(context: Context, text: String): Outcome {
        val app = context.applicationContext as AutoAgentApp
        return save(app, text, ReceiptSource.VOICE, null, "dictation:${System.currentTimeMillis()}")
    }

    private suspend fun save(app: AutoAgentApp, text: String, source: String, path: String?, ref: String): Outcome {
        val p = ReceiptParser.parse(text)
        val receipt = Receipt(
            merchant = p.merchant,
            dateText = p.dateText,
            dateMillis = p.dateMillis,
            total = p.total,
            currency = p.currency,
            source = source,
            rawText = text.take(8000),
            filePath = path,
            sourceRef = ref,
        )
        val id = withContext(Dispatchers.IO) { app.db.receipts().insert(receipt) }
        app.log("receipt", "Saved receipt: ${p.merchant.ifBlank { "?" }} ${p.total ?: ""} ${p.currency}", true)
        val missing = listOfNotNull(
            "merchant".takeIf { p.merchant.isBlank() },
            "date".takeIf { p.dateMillis == null },
            "total".takeIf { p.total == null },
        )
        val msg = if (missing.isEmpty()) "Receipt saved" else "Receipt saved; please fill in: ${missing.joinToString()}"
        return Outcome(receipt.copy(id = id), msg)
    }
}
