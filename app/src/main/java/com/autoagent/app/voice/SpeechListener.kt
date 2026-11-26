package com.autoagent.app.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.autoagent.app.util.L
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * One-shot speech recognition using Android's SpeechRecognizer. Prefers the on-device recognizer
 * (offline language pack) and always sets EXTRA_PREFER_OFFLINE. Must be used from the main thread.
 */
class SpeechListener(private val context: Context) {
    data class Heard(val text: String?, val error: Int?)

    val onDevice: Boolean =
        Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    val partial = MutableStateFlow("")
    val level = MutableStateFlow(0f)

    private var recognizer: SpeechRecognizer? = null

    private fun obtain(): SpeechRecognizer? {
        recognizer?.let { return it }
        val r = when {
            onDevice && Build.VERSION.SDK_INT >= 31 -> SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            SpeechRecognizer.isRecognitionAvailable(context) -> SpeechRecognizer.createSpeechRecognizer(context)
            else -> null
        }
        recognizer = r
        return r
    }

    fun available(): Boolean = onDevice || SpeechRecognizer.isRecognitionAvailable(context)

    suspend fun listenOnce(timeoutMs: Long = 15_000, extras: (Intent) -> Unit = {}): Heard {
        val r = obtain() ?: return Heard(null, SpeechRecognizer.ERROR_CLIENT)
        partial.value = ""
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                var done = false
                fun finish(h: Heard) {
                    if (done) return
                    done = true
                    level.value = 0f
                    if (cont.isActive) cont.resume(h)
                }
                r.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) { level.value = rmsdB }
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onError(error: Int) {
                        val p = partial.value
                        finish(if (p.isNotBlank()) Heard(p, null) else Heard(null, error))
                    }
                    override fun onResults(results: Bundle?) {
                        val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                        finish(Heard(t ?: partial.value.ifBlank { null }, if (t == null) SpeechRecognizer.ERROR_NO_MATCH else null))
                    }
                    override fun onPartialResults(partialResults: Bundle?) {
                        partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { partial.value = it }
                    }
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, com.autoagent.app.util.L.speechTag)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, com.autoagent.app.util.L.speechTag)
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1800L)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
                    extras(this)
                }
                r.startListening(intent)
                cont.invokeOnCancellation { runCatching { r.cancel() } }
            }
        } ?: run {
            runCatching { r.stopListening() }
            Heard(partial.value.ifBlank { null }, if (partial.value.isBlank()) SpeechRecognizer.ERROR_SPEECH_TIMEOUT else null)
        }
    }

    fun cancel() { runCatching { recognizer?.cancel() } }

    fun destroy() {
        runCatching { recognizer?.destroy() }
        recognizer = null
    }

    companion object {
        fun errorText(code: Int?): String = when (code) {
            null -> ""
            SpeechRecognizer.ERROR_AUDIO -> L.t("audio capture error (microphone busy or blocked during call)", "שגיאת מיקרופון (תפוס או חסום בזמן שיחה)")
            SpeechRecognizer.ERROR_CLIENT -> L.t("speech recognizer unavailable", "זיהוי הדיבור לא זמין")
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> L.t("microphone permission missing", "אין הרשאה למיקרופון")
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> L.t("offline language pack missing (recognizer wanted network)", "חסרה חבילת שפה לא מקוונת (הזיהוי ביקש אינטרנט)")
            SpeechRecognizer.ERROR_NO_MATCH -> L.t("no speech recognized", "לא זוהה דיבור")
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> L.t("recognizer busy", "זיהוי הדיבור עסוק")
            SpeechRecognizer.ERROR_SERVER -> L.t("recognizer server error", "שגיאת שרת בזיהוי הדיבור")
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> L.t("no speech heard", "לא נשמע דיבור")
            else -> if (Build.VERSION.SDK_INT >= 31 && code == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE) {
                L.t("offline language pack not installed", "חבילת השפה הלא מקוונת לא מותקנת")
            } else {
                L.t("recognizer error $code", "שגיאת זיהוי $code")
            }
        }
    }
}
