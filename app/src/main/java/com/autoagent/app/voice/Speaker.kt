package com.autoagent.app.voice

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Suspend-friendly wrapper around the platform TextToSpeech engine (works offline with installed voices). */
class Speaker(context: Context, usage: Int = AudioAttributes.USAGE_ASSISTANT) {
    private val ready = CompletableDeferred<Boolean>()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private val tts: TextToSpeech = run {
        val app = context.applicationContext
        val google = "com.google.android.tts"
        val hasGoogle = runCatching { app.packageManager.getPackageInfo(google, 0) }.isSuccess
        // Samsung's engine usually has no Hebrew voice; Google's does.
        if (hasGoogle) TextToSpeech(app, { status -> ready.complete(status == TextToSpeech.SUCCESS) }, google)
        else TextToSpeech(app) { status -> ready.complete(status == TextToSpeech.SUCCESS) }
    }

    init {
        tts.setAudioAttributes(
            AudioAttributes.Builder().setUsage(usage).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        )
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { utteranceId?.let { pending.remove(it)?.complete(Unit) } }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { utteranceId?.let { pending.remove(it)?.complete(Unit) } }
            override fun onError(utteranceId: String?, errorCode: Int) { utteranceId?.let { pending.remove(it)?.complete(Unit) } }
        })
    }

    /** Speaks [text] and suspends until playback finishes. Returns false if TTS is unavailable. */
    suspend fun speak(text: String): Boolean {
        val ok = withTimeoutOrNull(6_000) { ready.await() } ?: false
        if (!ok) return false
        applyLanguage()
        val id = UUID.randomUUID().toString()
        val done = CompletableDeferred<Unit>()
        pending[id] = done
        if (tts.speak(text, TextToSpeech.QUEUE_ADD, Bundle(), id) != TextToSpeech.SUCCESS) {
            pending.remove(id)
            return false
        }
        withTimeoutOrNull(30_000L + text.length * 120L) { done.await() }
        return true
    }

    /** Renders [text] to a WAV file (used to play speech into a call through the audio bridge). */
    suspend fun synthesizeToFile(text: String, out: java.io.File): Boolean {
        val ok = withTimeoutOrNull(6_000) { ready.await() } ?: false
        if (!ok) return false
        applyLanguage()
        val id = UUID.randomUUID().toString()
        val done = CompletableDeferred<Unit>()
        pending[id] = done
        if (tts.synthesizeToFile(text, Bundle(), out, id) != TextToSpeech.SUCCESS) { pending.remove(id); return false }
        withTimeoutOrNull(30_000L + text.length * 120L) { done.await() }
        return out.exists() && out.length() > 44
    }

    private fun applyLanguage() {
        runCatching {
            val loc = com.autoagent.app.util.L.speechLocale
            val res = tts.setLanguage(loc)
            if ((res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) && loc.language == "he") {
                tts.setLanguage(Locale.forLanguageTag("iw-IL"))
            }
        }
    }

    /** True when the engine has a voice for the app language (checked after init). */
    suspend fun hasVoiceForAppLanguage(): Boolean {
        val ok = withTimeoutOrNull(6_000) { ready.await() } ?: false
        if (!ok) return false
        val loc = com.autoagent.app.util.L.speechLocale
        val a = runCatching { tts.isLanguageAvailable(loc) }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
        val b = if (loc.language == "he") runCatching { tts.isLanguageAvailable(Locale.forLanguageTag("iw-IL")) }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED) else a
        return maxOf(a, b) >= TextToSpeech.LANG_AVAILABLE
    }

    fun stop() { runCatching { tts.stop() } }

    fun shutdown() {
        runCatching { tts.stop(); tts.shutdown() }
        pending.values.forEach { it.complete(Unit) }
        pending.clear()
    }
}
