package com.autoagent.app.calls

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import androidx.core.content.ContextCompat
import com.autoagent.app.AutoAgentApp
import com.autoagent.app.data.CallRecord
import com.autoagent.app.data.RuleIntent
import com.autoagent.app.util.L
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

        CallManager.assistantStatus.value = L.t(
            "Assistant active on speakerphone. Android does not let apps play audio into the call itself, " +
                "so the caller hears the assistant through your speaker and microphone.",
            "העוזר פעיל ברמקול. אנדרואיד לא מאפשר לאפליקציות להשמיע קול לתוך השיחה עצמה, " +
                "ולכן המתקשר שומע את העוזר דרך הרמקול והמיקרופון.",
        )
        control.setSpeaker(true)
        delay(700)

        val recorder = CallRecorder(context)
        if (!isTest && micOk) recorder.start()
        val speaker = Speaker(context, AudioAttributes.USAGE_VOICE_COMMUNICATION)
        val listener = SpeechListener(context)

        suspend fun say(text: String) {
            transcript.append(L.t("Assistant: ", "עוזר: ")).append(text).append('\n')
            CallManager.assistantStatus.value = L.t("Speaking: $text", "מדבר: $text")
            if (!speaker.speak(text)) notes += L.t("Text-to-speech engine unavailable.", "מנוע ההקראה לא זמין.")
        }

        try {
            say(CallBrain.GREETING)
            say(CallBrain.ASK)
            if (!micOk) {
                notes += L.t("Microphone permission is off, so the caller could not be heard.", "ההרשאה למיקרופון כבויה, ולכן לא היה אפשר לשמוע את המתקשר.")
            } else {
                var turns = 0
                var misses = 0
                while (turns < maxTurns && control.isActive()) {
                    CallManager.assistantStatus.value = L.t("Listening to caller…", "מקשיב למתקשר…")
                    val heard = listener.listenOnce(timeoutMs = 20_000)
                    val text = heard.text?.trim().orEmpty()
                    if (text.isEmpty()) {
                        heard.error?.let { notes += L.t("Speech: ", "דיבור: ") + SpeechListener.errorText(it) }
                        if (++misses >= 2 || !control.isActive()) break
                        say(CallBrain.NOT_HEARD)
                        continue
                    }
                    misses = 0
                    turns++
                    transcript.append(L.t("Caller: ", "מתקשר: ")).append(text).append('\n')
                    val reply = CallBrain.reply(text, rules)
                    intents += reply.intent
                    say(reply.text)
                    if (reply.intent == RuleIntent.TAKE_MESSAGE || reply.intent == RuleIntent.CALLBACK_TIME) {
                        CallManager.assistantStatus.value = L.t("Recording caller's message…", "רושם את ההודעה של המתקשר…")
                        val msg = listener.listenOnce(timeoutMs = 30_000).text?.trim().orEmpty()
                        if (msg.isNotEmpty()) {
                            transcript.append(L.t("Caller: ", "מתקשר: ")).append(msg).append('\n')
                            say(CallBrain.SAVED)
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
                val rec = if (isTest) CallRecorder.Result(null, L.t("Test call: audio recording skipped.", "שיחת בדיקה: ללא הקלטת קול.")) else if (micOk) recorder.stop()
                else CallRecorder.Result(null, L.t("Audio not saved: microphone permission is off.", "הקול לא נשמר: ההרשאה למיקרופון כבויה."))
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
                app.log("call", L.t("Assistant answered ${control.number}: ", "העוזר ענה ל-${control.number}: ") + intents.distinct().joinToString().ifEmpty { L.t("no request heard", "לא נשמעה בקשה") }, true)
                val callerTag = L.t("Caller:", "מתקשר:")
                val lastCaller = transcript.lines().lastOrNull { it.startsWith(callerTag) }?.removePrefix(callerTag)?.trim()
                Notifier.event(
                    context,
                    if (isTest) L.t("Assistant test call finished", "שיחת הבדיקה של העוזר הסתיימה") else L.t("Assistant answered ${control.number}", "העוזר ענה ל-${control.number}"),
                    (lastCaller?.let { "\"$it\" — " } ?: "") + L.t("Call back when you can. ", "כדאי לחזור כשנוח. ") + note,
                    "autoanswer",
                )
            }
        }
    }
}
