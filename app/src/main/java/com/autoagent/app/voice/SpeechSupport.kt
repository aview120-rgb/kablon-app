package com.autoagent.app.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import com.autoagent.app.util.L
import kotlinx.coroutines.flow.MutableStateFlow

/** Checks (on-device, offline) whether the speech pack and the TTS voice for the app language are installed. */
object SpeechSupport {
    /** null = unknown / not checked yet. */
    val offlinePack = MutableStateFlow<Boolean?>(null)
    val ttsVoice = MutableStateFlow<Boolean?>(null)

    private fun matches(tag: String, want: String): Boolean {
        val a = tag.lowercase().replace('_', '-').substringBefore('-').let { if (it == "iw") "he" else it }
        val b = want.lowercase().substringBefore('-').let { if (it == "iw") "he" else it }
        return a == b
    }

    /** Must be called on the main thread. */
    fun refresh(context: Context) {
        val want = L.speechTag
        if (Build.VERSION.SDK_INT >= 33 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
            val r = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE, want)
            runCatching {
                r.checkRecognitionSupport(intent, ContextCompat.getMainExecutor(context), object : RecognitionSupportCallback {
                    override fun onSupportResult(s: RecognitionSupport) {
                        offlinePack.value = s.installedOnDeviceLanguages.any { matches(it, want) }
                        r.destroy()
                    }
                    override fun onError(error: Int) {
                        offlinePack.value = false
                        r.destroy()
                    }
                })
            }.onFailure { offlinePack.value = false; r.destroy() }
        } else {
            offlinePack.value = Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        }
    }

    suspend fun refreshTts(context: Context) {
        val sp = Speaker(context)
        ttsVoice.value = sp.hasVoiceForAppLanguage()
        sp.shutdown()
    }

    /** Asks Android to download the offline recognition model for the app language (Android 13+). */
    fun download(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 33 || !SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) return false
        val r = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        r.triggerModelDownload(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE, L.speechTag))
        r.destroy()
        return true
    }
}
