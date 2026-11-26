package com.autoagent.app.receipts

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** On-device OCR using the bundled ML Kit Latin text model (no download, no network). */
object OcrEngine {
    suspend fun recognize(context: Context, uri: Uri): String {
        val image = InputImage.fromFilePath(context, uri)
        val client = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        return try {
            suspendCancellableCoroutine { cont ->
                client.process(image)
                    .addOnSuccessListener { result ->
                        // Keep reading order: blocks top-to-bottom, lines within blocks.
                        val text = result.textBlocks
                            .sortedBy { it.boundingBox?.top ?: 0 }
                            .flatMap { b -> b.lines.map { it.text } }
                            .joinToString("\n")
                        cont.resume(text)
                    }
                    .addOnFailureListener { cont.resumeWithException(it) }
            }
        } finally {
            client.close()
        }
    }
}
