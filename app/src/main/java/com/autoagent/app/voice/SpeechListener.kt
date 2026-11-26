package com.autoagent.app.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.autoagent.app.AutoAgentApp
import com.autoagent.app.util.L
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * One-shot speech recognition. Order of preference:
 * 1. the on-device recognizer (works without internet) when it has the app language;
 * 2. the phone's regular recognizer (Google, may use the internet) when online speech is allowed.
 * If the on-device recognizer answers "language not supported" (error 12/13) it switches to (2) and retries.
 * Must be used from the main thread.
 */
class SpeechListener(private val context: Context) {
    data class Heard(val text: String?, val error: Int?)

    private val allowOnline: Boolean
        get() = (context.applicationContext as? AutoAgentApp)?.settings?.current?.allowOnlineSpeech ?: true

    val onDevice: Boolean =
        Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    /** True once the on-device recognizer proved it lacks the language. */
    private var onDeviceUnusable = SpeechSupport.offlinePack.value == false
    var usingOnline: Boolean = false
        private set

    val partial = MutableStateFlow("")
    val level = MutableStateFlow(0f)

    private var recognizer: SpeechRecognizer? = null

    private fun obtain(): SpeechRecognizer? {
        recognizer?.let { return it }
        val useOnDevice = onDevice && Build.VERSION.SDK_INT >= 31 && (!onDeviceUnusable || !allowOnline)
        val r = when {
            useOnDevice -> { usingOnline = false; SpeechRecognizer.createOnDeviceSpeechRecognizer(context) }
            SpeechRecognizer.isRecognitionAvailable(context) -> { usingOnline = true; SpeechRecognizer.createSpeechRecognizer(context) }
            else -> null
        }
        recognizer = r
        return r
    }

    fun available(): Boolean = onDevice || SpeechRecognizer.isRecognitionAvailable(context)

    private fun isLanguageError(e: Int?) = e == ERROR_LANGUAGE_NOT_SUPPORTED || e == ERROR_LANGUAGE_UNAVAILABLE

    suspend fun listenOnce(timeoutMs: Long = 15_000, extras: (Intent) -> Unit = {}): Heard {
        val first = listenWith(timeoutMs, extras)
        if (isLanguageError(first.error) && !usingOnline && allowOnline && SpeechRecognizer.isRecognitionAvailable(context)) {
            onDeviceUnusable = true
            SpeechSupport.offlinePack.value = false
            destroy()
            return listenWith(timeoutMs, extras)
        }
        return first
    }

    private suspend fun listenWith(timeoutMs: Long, extras: (Intent) -> Unit): Heard {
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
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, L.speechTag)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, L.speechTag)
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, !allowOnline || !onDeviceUnusable)
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
        /** SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED / ERROR_LANGUAGE_UNAVAILABLE (API 31). */
        const val ERROR_LANGUAGE_NOT_SUPPORTED = 12
        const val ERROR_LANGUAGE_UNAVAILABLE = 13

        fun errorText(code: Int?): String = when (code) {
            null -> ""
            SpeechRecognizer.ERROR_AUDIO -> L.t("audio capture error (microphone busy or blocked during call)", "שגיאת מיקרופון (תפוס או חסום בזמן שיחה)")
            SpeechRecognizer.ERROR_CLIENT -> L.t("speech recognizer unavailable", "זיהוי הדיבור לא זמין")
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> L.t("microphone permission missing", "אין הרשאה למיקרופון")
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                L.t("no internet, and the offline language pack is missing", "אין אינטרנט, וחבילת השפה הלא מקוונת חסרה")
            SpeechRecognizer.ERROR_NO_MATCH -> L.t("no speech recognized", "לא זוהה דיבור")
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> L.t("recognizer busy", "זיהוי הדיבור עסוק")
            SpeechRecognizer.ERROR_SERVER -> L.t("recognizer server error", "שגיאת שרת בזיהוי הדיבור")
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> L.t("no speech heard", "לא נשמע דיבור")
            ERROR_LANGUAGE_NOT_SUPPORTED -> L.t(
                "this phone's recognizer does not support the language (install it offline, or allow online recognition)",
                "זיהוי הדיבור בטלפון לא תומך בשפה (צריך להתקין חבילת עברית, או לאפשר זיהוי עם אינטרנט)",
            )
            ERROR_LANGUAGE_UNAVAILABLE -> L.t("offline language pack not installed", "חבילת השפה הלא מקוונת לא מותקנת")
            else -> L.t("recognizer error $code", "שגיאת זיהוי $code")
        }
    }
}
