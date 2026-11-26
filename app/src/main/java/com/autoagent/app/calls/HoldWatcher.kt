package com.autoagent.app.calls

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.RecognizerIntent
import com.autoagent.app.AutoAgentApp
import com.autoagent.app.priv.CallBridge
import com.autoagent.app.util.HebrewText
import com.autoagent.app.util.L
import com.autoagent.app.util.Notifier
import com.autoagent.app.voice.SpeechListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/** Decides whether a recognized phrase on the line is a recorded hold message or a live person. */
object HoldClassifier {
    enum class Kind { IVR, HUMAN, UNKNOWN }

    private val ivr = listOf(
        "שיחתך חשובה", "שיחתכם חשובה", "שיחתך חשובה לנו", "הנך ממתין", "הנכם ממתינים", "אתה ממתין", "את ממתינה", "אנא המתן", "אנא המתיני",
        "אנא המתינו", "נא להמתין", "נא המתן", "המתן על הקו", "המתינו על הקו", "המתנה", "ממתינים", "לחץ", "לחצי", "לחצו", "הקש", "הקישי", "הקישו",
        "שלוחה", "תפריט", "בחר", "בחרי", "בחרו", "מספר תעודת", "תעודת זהות", "מוקלטת", "מוקלטות", "בהקדם האפשרי", "הנציג הבא", "הנציגה הבאה",
        "הנציגים הבאים", "נציג פנוי", "זמן ההמתנה", "זמן המתנה", "מקומך בתור", "מקומכם בתור", "מיקומך בתור", "בתור", "ברוכים הבאים", "ברוך הבא",
        "למידע נוסף", "באתר האינטרנט", "באפליקציה", "שעות הפעילות", "כל הנציגים", "עסוקים", "תודה על הסבלנות", "שירות לקוחות", "לשירותך",
        "your call is important", "please hold", "please wait", "press", "menu", "next available", "recorded", "queue", "all our agents",
        "busy", "welcome to", "thank you for your patience", "estimated wait",
    )
    private val human = listOf(
        "שלום", "מדבר", "מדברת", "מדבר עם", "מדברים", "איך אפשר לעזור", "במה אפשר לעזור", "איך אוכל לעזור", "במה אוכל לעזור", "שמי", "קוראים לי",
        "נעים מאוד", "הלו", "בוקר טוב", "ערב טוב", "צהריים טובים", "כן שלום", "שומעת", "שומע", "אתה שומע", "את שומעת", "על הקו", "איתך",
        "hello", "how can i help", "how may i help", "speaking", "my name is", "good morning", "good afternoon", "can you hear me",
    )

    fun classify(text: String): Kind {
        val n = HebrewText.normalize(text)
        if (n.isBlank()) return Kind.UNKNOWN
        if (ivr.any { HebrewText.containsPhrase(n, HebrewText.normalize(it)) }) return Kind.IVR
        if (human.any { HebrewText.containsPhrase(n, HebrewText.normalize(it)) }) return Kind.HUMAN
        return Kind.UNKNOWN
    }
}

/**
 * Listens to an outgoing call while you wait on hold and alerts you (beep in the call, vibration and a
 * notification) when a live person starts talking. Uses the Shizuku bridge to hear the line when available,
 * otherwise the speakerphone + microphone.
 */
object HoldWatcher {
    val status = MutableStateFlow<String?>(null)
    @Volatile var armedForNextCall: Boolean = false
    private var job: Job? = null

    val active: Boolean get() = job?.isActive == true

    fun stop() {
        job?.cancel()
        job = null
        status.value = null
    }

    fun start(context: Context, isCallActive: () -> Boolean) {
        if (active) return
        val app = context.applicationContext as AutoAgentApp
        job = app.appScope.launch(Dispatchers.Main) {
            val listener = SpeechListener(context)
            val capture = if (CallBridge.canHearCall()) CallBridge.startCapture(File(context.cacheDir, "hold.wav")) else null
            if (capture == null) CallManager.setSpeaker(true)
            status.value = if (capture != null) L.t("Watching the line for a live person (bridge)…", "מקשיב לקו ומחכה לנציג אנושי (דרך Shizuku)…")
            else L.t("Watching on speakerphone for a live person… keep the phone near you.", "מקשיב ברמקול ומחכה לנציג אנושי… כדאי שהטלפון יהיה לידך.")
            val started = System.currentTimeMillis()
            var lastIvr = System.currentTimeMillis()
            try {
                while (isActive && isCallActive()) {
                    val heard = if (capture != null && Build.VERSION.SDK_INT >= 33) {
                        val pipe = capture.newListenPipe()
                        try {
                            listener.listenOnce(25_000) { i ->
                                i.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe)
                                i.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                                i.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, android.media.AudioFormat.ENCODING_PCM_16BIT)
                                i.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, capture.sampleRate)
                            }
                        } finally { capture.closeSink(); runCatching { pipe.close() } }
                    } else {
                        listener.listenOnce(25_000)
                    }
                    val text = heard.text?.trim().orEmpty()
                    if (text.isEmpty()) { delay(200); continue }
                    when (HoldClassifier.classify(text)) {
                        HoldClassifier.Kind.IVR -> {
                            lastIvr = System.currentTimeMillis()
                            status.value = L.t("Still on hold: \"$text\"", "עדיין בהמתנה: \"$text\"")
                        }
                        HoldClassifier.Kind.HUMAN -> { alert(context, text, sure = true); break }
                        HoldClassifier.Kind.UNKNOWN -> {
                            // A non-recorded sentence after a while on hold is probably a person.
                            val words = text.split(' ').size
                            if (System.currentTimeMillis() - started > 15_000 && System.currentTimeMillis() - lastIvr > 8_000 && words >= 3) {
                                alert(context, text, sure = false); break
                            }
                            status.value = L.t("Heard: \"$text\"", "שמעתי: \"$text\"")
                        }
                    }
                }
            } finally {
                listener.destroy()
                capture?.stop()
                if (status.value?.startsWith("!") != true) status.value = null
            }
        }
    }

    private fun alert(context: Context, text: String, sure: Boolean) {
        status.value = "!" + if (sure) L.t("A person answered: \"$text\"", "נציג ענה: \"$text\"")
        else L.t("Probably a person: \"$text\"", "כנראה שנציג ענה: \"$text\"")
        runCatching {
            val tg = ToneGenerator(AudioManager.STREAM_VOICE_CALL, 100)
            tg.startTone(ToneGenerator.TONE_PROP_BEEP2, 1200)
        }
        runCatching {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 500, 200, 500, 200, 800), -1))
        }
        Notifier.event(
            context,
            if (sure) L.t("A person answered!", "נציג ענה לטלפון!") else L.t("Someone may have answered", "נראה שמישהו ענה"),
            "\"$text\"",
            null,
        )
    }
}
