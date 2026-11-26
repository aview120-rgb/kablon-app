package com.autoagent.app.calls

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import androidx.core.content.ContextCompat
import com.autoagent.app.AutoAgentApp
import com.autoagent.app.data.CallRecord
import com.autoagent.app.data.RuleIntent
import com.autoagent.app.util.Notifier
import com.autoagent.app.voice.Speaker
import com.autoagent.app.voice.SpeechListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Minimal control surface so the same assistant script runs on a real Telecom call or a self-managed test call. */
interface CallControl {
    val number: String
    fun setSpeaker(on: Boolean)
    fun hangUp()
    fun isActive(): Boolean
}

/**
 * The scripted assistant: speaks the mandatory disclosure first, asks what the caller needs, answers with
 * local keyword templates, promises a callback, then saves number + transcript (+ audio if the OS allows).
 */
class AssistantSession(
    private val context: Context,
    private val control: CallControl,
    private val isTest: Boolean,
) {
    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        job = scope.launch(Dispatchers.Main) { run() }
    }

    fun stop() {
        job?.cancel()
    }

    private suspend fun run() {
        val app = context.applicationContext as AutoAgentApp
        val startedAt = System.currentTimeMillis()
        val transcript = StringBuilder()
        val intents = mutableListOf<String>()
        val notes = mutableListOf<String>()
        val rules = withContext(Dispatchers.IO) { app.db.rules().enabled() }
        val maxTurns = app.settings.current.maxTurns
        val micOk = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        CallManager.assistantStatus.value =
            "Assistant active on speakerphone. Android does not let apps play audio into the call itself, " +
            "so the caller hears the assistant through your speaker and microphone."
        control.setSpeaker(true)
        delay(700)

        val recorder = CallRecorder(context)
        if (!isTest && micOk) recorder.start()
        val speaker = Speaker(context, AudioAttributes.USAGE_VOICE_COMMUNICATION)
        val listener = SpeechListener(context)

        suspend fun say(text: String) {
            transcript.append("Assistant: ").append(text).append('\n')
            CallManager.assistantStatus.value = "Speaking: $text"
            if (!speaker.speak(text)) notes += "Text-to-speech engine unavailable."
        }

        try {
            say(CallBrain.GREETING)
            say(CallBrain.ASK)
            if (!micOk) {
                notes += "Microphone permission is off, so the caller could not be heard."
            } else {
                var turns = 0
                var misses = 0
                while (turns < maxTurns && control.isActive()) {
                    CallManager.assistantStatus.value = "Listening to caller…"
                    val heard = listener.listenOnce(timeoutMs = 20_000)
                    val text = heard.text?.trim().orEmpty()
                    if (text.isEmpty()) {
                        heard.error?.let { notes += "Speech: " + SpeechListener.errorText(it) }
                        if (++misses >= 2 || !control.isActive()) break
                        say(CallBrain.NOT_HEARD)
                        continue
                    }
                    misses = 0
                    turns++
                    transcript.append("Caller: ").append(text).append('\n')
                    val reply = CallBrain.reply(text, rules)
                    intents += reply.intent
                    say(reply.text)
                    if (reply.intent == RuleIntent.TAKE_MESSAGE || reply.intent == RuleIntent.CALLBACK_TIME) {
                        CallManager.assistantStatus.value = "Recording caller's message…"
                        val msg = listener.listenOnce(timeoutMs = 30_000).text?.trim().orEmpty()
                        if (msg.isNotEmpty()) {
                            transcript.append("Caller: ").append(msg).append('\n')
                            say("Got it. I saved your message.")
                        }
                    } else if (turns < maxTurns && control.isActive()) {
                        say(CallBrain.ANYTHING_ELSE)
                    }
                }
            }
            if (control.isActive()) say(CallBrain.CLOSING)
        } finally {
            withContext(NonCancellable) {
                listener.destroy()
                val rec = if (isTest) CallRecorder.Result(null, "Test call: audio recording skipped.") else if (micOk) recorder.stop()
                else CallRecorder.Result(null, "Audio not saved: microphone permission is off.")
                speaker.shutdown()
                runCatching { control.hangUp() }
                CallManager.assistantStatus.value = null
                val note = (listOf(rec.note) + notes.distinct()).joinToString(" ")
                val record = CallRecord(
                    number = control.number,
                    startedAt = startedAt,
                    endedAt = System.currentTimeMillis(),
                    transcript = transcript.toString().trim(),
                    audioPath = rec.path,
                    audioNote = note,
                    intents = intents.distinct().joinToString(","),
                    isTest = isTest,
                )
                withContext(Dispatchers.IO) { app.db.calls().insert(record) }
                app.log("call", "Assistant answered ${control.number}: ${intents.distinct().joinToString().ifEmpty { "no request heard" }}", true)
                val lastCaller = transcript.lines().lastOrNull { it.startsWith("Caller:") }?.removePrefix("Caller:")?.trim()
                Notifier.event(
                    context,
                    if (isTest) "Assistant test call finished" else "Assistant answered ${control.number}",
                    (lastCaller?.let { "\"$it\" — " } ?: "") + "Call back when you can. $note",
                    "autoanswer",
                )
            }
        }
    }
}
