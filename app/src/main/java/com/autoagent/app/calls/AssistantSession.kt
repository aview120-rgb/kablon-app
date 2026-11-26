package com.autoagent.app.calls

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.os.Build
import android.speech.RecognizerIntent
import androidx.core.content.ContextCompat
import com.autoagent.app.AutoAgentApp
import com.autoagent.app.data.CallRecord
import com.autoagent.app.data.RuleIntent
import com.autoagent.app.priv.CallBridge
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
import java.io.File

/** Minimal control surface so the same assistant script runs on a real Telecom call or a self-managed test call. */
interface CallControl {
    val number: String
    fun setSpeaker(on: Boolean)
    fun hangUp()
    fun isActive(): Boolean
}

/**
 * The scripted assistant: speaks the mandatory disclosure first, then talks with the caller using local
 * keyword rules, takes a message, promises a callback, and saves number + transcript (+ audio).
 *
 * Audio path, best first:
 *  - Bridge (Shizuku, shell user): speech goes straight into the call and the line is heard and recorded.
 *  - Speakerphone: the caller hears the assistant through the speaker/mic; transcript only.
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
        val maxTurns = app.settings.current.maxTurns + 1
        val micOk = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        var bridge = !isTest && CallBridge.canSpeakIntoCall()
        val speaker = Speaker(context, AudioAttributes.USAGE_VOICE_COMMUNICATION)
        val listener = SpeechListener(context)
        var capture: CallBridge.Capture? = null
        val wav = File(File(context.filesDir, "calls").apply { mkdirs() }, "call_${startedAt}.wav")
        var recorder: CallRecorder? = null

        if (bridge) {
            CallManager.assistantStatus.value = L.t(
                "Assistant is speaking directly into the call (Shizuku bridge).",
                "העוזר מדבר ישירות לתוך השיחה (דרך Shizuku).",
            )
            capture = if (CallBridge.canHearCall()) CallBridge.startCapture(wav) else null
        } else {
            CallManager.assistantStatus.value = L.t(
                "Assistant active on speakerphone. Android does not let ordinary apps play audio into the call, " +
                    "so the caller hears the assistant through your speaker and microphone.",
                "העוזר פעיל ברמקול. אנדרואיד לא מאפשר לאפליקציה רגילה להשמיע קול לתוך השיחה, " +
                    "ולכן המתקשר שומע את העוזר דרך הרמקול והמיקרופון.",
            )
            control.setSpeaker(true)
            delay(700)
            if (!isTest && micOk) recorder = CallRecorder(context).also { it.start() }
        }

        fun switchToSpeaker(reason: String) {
            if (!bridge) return
            bridge = false
            notes += L.t("Bridge failed ($reason); switched to speakerphone.", "הגישור נכשל ($reason); עברתי לרמקול.")
            control.setSpeaker(true)
        }

        suspend fun say(text: String) {
            transcript.append(L.t("Assistant: ", "עוזר: ")).append(text).append('\n')
            CallManager.assistantStatus.value = L.t("Speaking: $text", "מדבר: $text")
            if (bridge) {
                val err = CallBridge.speakIntoCall(speaker, text)
                if (err.isEmpty()) return
                switchToSpeaker(err)
            }
            if (!speaker.speak(text)) notes += L.t("Text-to-speech engine unavailable.", "מנוע ההקראה לא זמין.")
        }

        suspend fun hear(timeoutMs: Long): SpeechListener.Heard {
            val cap = capture
            if (bridge && cap != null && Build.VERSION.SDK_INT >= 33) {
                val pipe = cap.newListenPipe()
                val h = try {
                    listener.listenOnce(timeoutMs) { i ->
                        i.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe)
                        i.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                        i.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, android.media.AudioFormat.ENCODING_PCM_16BIT)
                        i.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, cap.sampleRate)
                    }
                } finally {
                    cap.closeSink()
                    runCatching { pipe.close() }
                }
                if (h.text != null || h.error == android.speech.SpeechRecognizer.ERROR_SPEECH_TIMEOUT ||
                    h.error == android.speech.SpeechRecognizer.ERROR_NO_MATCH
                ) return h
                // The recognizer could not read the line audio; hear the caller via the microphone instead.
                notes += L.t("Line audio not readable by the recognizer; listening via microphone.", "זיהוי הדיבור לא קרא את קול הקו; מקשיב דרך המיקרופון.")
                capture?.stop(); capture = null
                control.setSpeaker(true)
            }
            return listener.listenOnce(timeoutMs)
        }

        suspend fun takeMessage() {
            CallManager.assistantStatus.value = L.t("Recording caller's message…", "רושם את ההודעה של המתקשר…")
            val msg = hear(30_000).text?.trim().orEmpty()
            if (msg.isNotEmpty()) {
                transcript.append(L.t("Caller: ", "מתקשר: ")).append(msg).append('\n')
                val r = CallBrain.reply(msg, rules)
                if (r.intent == RuleIntent.GOODBYE) { say(r.text); throw EndCall() }
                say(CallBrain.SAVED)
            }
        }

        var closedByRule = false
        try {
            say(CallBrain.GREETING)
            say(CallBrain.ASK)
            if (!micOk && capture == null) {
                notes += L.t("Microphone permission is off, so the caller could not be heard.", "ההרשאה למיקרופון כבויה, ולכן לא היה אפשר לשמוע את המתקשר.")
            } else {
                var turns = 0
                var misses = 0
                while (turns < maxTurns && control.isActive()) {
                    CallManager.assistantStatus.value = L.t("Listening to caller…", "מקשיב למתקשר…")
                    val heard = hear(20_000)
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
                    if (reply.intent == RuleIntent.URGENT || reply.intent == RuleIntent.DELIVERY) {
                        Notifier.event(
                            context,
                            if (reply.intent == RuleIntent.URGENT) L.t("URGENT call from ${control.number}", "שיחה דחופה מ-${control.number}")
                            else L.t("Delivery on the line: ${control.number}", "שליח על הקו: ${control.number}"),
                            "\"$text\"",
                            "autoanswer",
                        )
                    }
                    say(reply.text)
                    when {
                        reply.intent in RuleIntent.ENDS_CALL -> { closedByRule = true; break }
                        reply.intent in RuleIntent.EXPECTS_MESSAGE -> takeMessage()
                        turns < maxTurns && control.isActive() -> say(CallBrain.ANYTHING_ELSE)
                    }
                }
            }
            if (control.isActive() && !closedByRule) say(CallBrain.CLOSING)
        } catch (_: EndCall) {
        } finally {
            withContext(NonCancellable) {
                listener.destroy()
                val cap = capture
                val rec: CallRecorder.Result = when {
                    isTest -> CallRecorder.Result(null, L.t("Test call: audio recording skipped.", "שיחת בדיקה: ללא הקלטת קול."))
                    cap != null -> {
                        cap.stop()
                        if (cap.seconds > 1) CallRecorder.Result(wav.absolutePath, L.t("Both sides recorded (Shizuku bridge).", "שני הצדדים הוקלטו (דרך Shizuku)."))
                        else { wav.delete(); CallRecorder.Result(null, L.t("The bridge produced no call audio. Transcript only.", "הגישור לא קיבל קול מהשיחה. נשמר תמלול בלבד.")) }
                    }
                    recorder != null -> recorder.stop()
                    micOk -> CallRecorder.Result(null, L.t("Audio not saved.", "הקול לא נשמר."))
                    else -> CallRecorder.Result(null, L.t("Audio not saved: microphone permission is off.", "הקול לא נשמר: ההרשאה למיקרופון כבויה."))
                }
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
                val lastCaller = transcript.lines().lastOrNull { it.startsWith("Caller:") || it.startsWith("מתקשר:") }?.substringAfter(':')?.trim()
                Notifier.event(
                    context,
                    if (isTest) L.t("Assistant test call finished", "שיחת הבדיקה של העוזר הסתיימה") else L.t("Assistant answered ${control.number}", "העוזר ענה ל-${control.number}"),
                    (lastCaller?.let { "\"$it\" — " } ?: "") + L.t("Call back when you can. ", "כדאי לחזור כשנוח. ") + note,
                    "autoanswer",
                )
            }
        }
    }

    private class EndCall : RuntimeException()
}
