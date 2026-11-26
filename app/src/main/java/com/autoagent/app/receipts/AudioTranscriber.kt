package com.autoagent.app.receipts

import com.autoagent.app.util.L
import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.speech.RecognizerIntent
import com.autoagent.app.voice.SpeechListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteOrder

/**
 * Transcribes a shared voice note on-device: decodes it to 16-bit mono PCM with MediaCodec and feeds it
 * to the on-device SpeechRecognizer via EXTRA_AUDIO_SOURCE (Android 13+).
 */
object AudioTranscriber {
    sealed interface Result {
        data class Text(val text: String) : Result
        data class Unsupported(val reason: String) : Result
    }

    suspend fun transcribe(context: Context, uri: Uri): Result {
        if (Build.VERSION.SDK_INT < 33) {
            return Result.Unsupported(L.t("Transcribing a shared audio file needs Android 13 or newer. Use \"Dictate receipt\" instead.", "תמלול קובץ קול משותף דורש אנדרואיד 13 ומעלה. אפשר להשתמש ב\"הכתבת קבלה\" במקום."))
        }
        val listener = SpeechListener(context)
        if (!listener.onDevice) {
            return Result.Unsupported(L.t("On-device speech recognition is not available. Install the offline language pack (Permissions screen).", "זיהוי דיבור במכשיר לא זמין. צריך להתקין את חבילת השפה הלא מקוונת (במסך ההרשאות)."))
        }
        val pcm = File(context.cacheDir, "voice_${System.currentTimeMillis()}.pcm")
        val (rate, _) = withContext(Dispatchers.IO) { decodeToPcm(context, uri, pcm) }
        val pfd = ParcelFileDescriptor.open(pcm, ParcelFileDescriptor.MODE_READ_ONLY)
        return try {
            val heard = withContext(Dispatchers.Main) {
                listener.listenOnce(timeoutMs = 120_000) { intent ->
                    intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pfd)
                    intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                    intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                    intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, rate)
                    intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 4000L)
                }
            }
            val text = heard.text
            if (text.isNullOrBlank()) Result.Unsupported(L.t("Could not transcribe: ", "לא הצלחתי לתמלל: ") + SpeechListener.errorText(heard.error))
            else Result.Text(text)
        } finally {
            withContext(Dispatchers.Main) { listener.destroy() }
            runCatching { pfd.close() }
            pcm.delete()
        }
    }

    /** Returns (sampleRate, channels) of the written mono PCM. */
    private fun decodeToPcm(context: Context, uri: Uri, out: File): Pair<Int, Int> {
        val extractor = MediaExtractor()
        extractor.setDataSource(context, uri, null)
        val track = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: error(L.t("No audio track in shared file", "אין ערוץ קול בקובץ ששותף"))
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(format, null, null, 0)
        codec.start()
        var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        FileOutputStream(out).use { fos ->
            while (!outputDone) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = codec.outputFormat
                        rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                    outIdx >= 0 -> {
                        val buf = codec.getOutputBuffer(outIdx)!!
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        val shorts = buf.order(ByteOrder.nativeOrder()).asShortBuffer()
                        val frames = shorts.remaining() / channels.coerceAtLeast(1)
                        val mono = ByteArray(frames * 2)
                        for (i in 0 until frames) {
                            var sum = 0
                            for (c in 0 until channels) sum += shorts.get(i * channels + c)
                            val s = (sum / channels.coerceAtLeast(1)).toShort().toInt()
                            mono[i * 2] = (s and 0xff).toByte()
                            mono[i * 2 + 1] = ((s shr 8) and 0xff).toByte()
                        }
                        fos.write(mono)
                        codec.releaseOutputBuffer(outIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
        }
        codec.stop()
        codec.release()
        extractor.release()
        return rate to 1
    }
}
