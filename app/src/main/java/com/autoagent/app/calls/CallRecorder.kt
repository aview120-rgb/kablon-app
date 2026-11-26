package com.autoagent.app.calls

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.autoagent.app.util.L
import java.io.File

/**
 * Attempts to record call audio with the VOICE_CALL source. On normal (non-system) installs Android
 * refuses this source because it needs CAPTURE_AUDIO_OUTPUT; then we keep transcript only and say why.
 */
class CallRecorder(private val context: Context) {
    data class Result(val path: String?, val note: String)

    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var maxAmplitude = 0
    private var failure: String? = null
    private val handler = Handler(Looper.getMainLooper())
    private val sampler = object : Runnable {
        override fun run() {
            recorder?.let { r -> runCatching { maxAmplitude = maxOf(maxAmplitude, r.maxAmplitude) } }
            if (recorder != null) handler.postDelayed(this, 400)
        }
    }

    fun start() {
        val dir = File(context.filesDir, "calls").apply { mkdirs() }
        val f = File(dir, "call_${System.currentTimeMillis()}.m4a")
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        try {
            r.setAudioSource(MediaRecorder.AudioSource.VOICE_CALL)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioSamplingRate(16_000)
            r.setAudioEncodingBitRate(48_000)
            r.setOutputFile(f.absolutePath)
            r.prepare()
            r.start()
            recorder = r
            file = f
            handler.postDelayed(sampler, 400)
        } catch (e: Exception) {
            runCatching { r.release() }
            f.delete()
            failure = L.t(
                "Audio not saved: Android blocked call-audio capture for this app " +
                    "(VOICE_CALL needs the system-only CAPTURE_AUDIO_OUTPUT permission). Transcript only.",
                "הקול לא נשמר: אנדרואיד חוסם הקלטת שיחות לאפליקציות רגילות " +
                    "(נדרשת ההרשאה CAPTURE_AUDIO_OUTPUT שזמינה רק למערכת). נשמר תמלול בלבד.",
            )
        }
    }

    fun stop(): Result {
        failure?.let { return Result(null, it) }
        val r = recorder ?: return Result(null, L.t("Audio recording was not started.", "הקלטת הקול לא התחילה."))
        recorder = null
        handler.removeCallbacks(sampler)
        val stopped = runCatching { r.stop() }.isSuccess
        runCatching { r.release() }
        val f = file
        return when {
            f == null || !stopped || f.length() < 1024 -> {
                f?.delete()
                Result(null, L.t("Audio not saved: the OS gave this app no call audio. Transcript only.", "הקול לא נשמר: מערכת ההפעלה לא העבירה קול מהשיחה. נשמר תמלול בלבד."))
            }
            maxAmplitude == 0 -> {
                f.delete()
                Result(null, L.t("Audio not saved: the OS delivered only silence for the call (call audio muted for apps). Transcript only.", "הקול לא נשמר: התקבל רק שקט (קול השיחה מושתק לאפליקציות). נשמר תמלול בלבד."))
            }
            else -> Result(f.absolutePath, L.t("Call audio saved.", "קול השיחה נשמר."))
        }
    }
}
