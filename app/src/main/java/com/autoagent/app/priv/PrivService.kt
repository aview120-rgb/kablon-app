package com.autoagent.app.priv

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import java.io.FileOutputStream
import kotlin.system.exitProcess

/**
 * Shizuku "user service": this class is instantiated in a separate process that runs as the shell user
 * (uid 2000). The shell user holds MODIFY_PHONE_STATE and CAPTURE_AUDIO_OUTPUT, which ordinary apps cannot get,
 * so from here audio can be played to the call's uplink ("in-call music" output) and the call audio can be read.
 * Whether a given phone routes that output to the caller is up to its audio hardware; probe() reports it.
 */
class PrivService() : IPrivService.Stub() {
    private var context: Context? = null
    @Volatile private var capturing = false
    private var captureThread: Thread? = null

    /** Shizuku 13 passes a Context when this constructor exists. */
    constructor(context: Context) : this() {
        this.context = context
    }

    override fun destroy() {
        stopCapture()
        exitProcess(0)
    }

    private fun ctx(): Context? = context ?: runCatching {
        val at = Class.forName("android.app.ActivityThread")
        val thread = at.getMethod("currentActivityThread").invoke(null) ?: at.getMethod("systemMain").invoke(null)
        at.getMethod("getSystemContext").invoke(thread) as Context
    }.getOrNull()

    private fun shellContext(): Context? = ctx()?.let { c -> runCatching { c.createPackageContext("com.android.shell", 0) }.getOrNull() ?: c }

    private fun uplinkDevice(): AudioDeviceInfo? {
        val am = ctx()?.getSystemService(AudioManager::class.java) ?: return null
        return am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.type == AudioDeviceInfo.TYPE_TELEPHONY }
    }

    override fun probe(): String {
        val dev = runCatching { uplinkDevice() }.getOrNull()
        return buildString {
            append("uid=").append(Process.myUid())
            append(";sdk=").append(Build.VERSION.SDK_INT)
            append(";uplink=").append(dev != null)
            if (dev != null) append(";uplinkName=").append(dev.productName)
            append(";context=").append(ctx() != null)
        }
    }

    override fun play(pcm: ParcelFileDescriptor?, sampleRate: Int): String {
        if (pcm == null) return "no audio"
        val data = ParcelFileDescriptor.AutoCloseInputStream(pcm).use { it.readBytes() }
        if (data.isEmpty()) return "empty audio"
        val dev = runCatching { uplinkDevice() }.getOrNull() ?: return "this phone exposes no in-call audio output"
        val minBuf = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minBuf * 4, 16_384))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (e: Exception) {
            return "AudioTrack: ${e.message}"
        }
        return try {
            if (!track.setPreferredDevice(dev)) return "could not route to the in-call output"
            track.play()
            var off = 0
            while (off < data.size) {
                val n = track.write(data, off, minOf(8192, data.size - off))
                if (n < 0) return "write error $n"
                off += n
            }
            val frames = data.size / 2
            val deadline = System.currentTimeMillis() + frames * 1000L / sampleRate + 3000
            while (track.playbackHeadPosition < frames && System.currentTimeMillis() < deadline) Thread.sleep(40)
            ""
        } catch (e: Exception) {
            "play: ${e.message}"
        } finally {
            runCatching { track.stop() }
            track.release()
        }
    }

    @SuppressLint("MissingPermission")
    private fun openRecorder(sampleRate: Int): AudioRecord? {
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val size = maxOf(minBuf * 4, 16_384)
        val attempts = mutableListOf<() -> AudioRecord>()
        if (Build.VERSION.SDK_INT >= 31) {
            shellContext()?.let { sc ->
                attempts += {
                    AudioRecord.Builder()
                        .setContext(sc)
                        .setAudioSource(MediaRecorder.AudioSource.VOICE_CALL)
                        .setAudioFormat(AudioFormat.Builder().setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                        .setBufferSizeInBytes(size)
                        .build()
                }
            }
        }
        attempts += { AudioRecord(MediaRecorder.AudioSource.VOICE_CALL, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size) }
        attempts += { AudioRecord(MediaRecorder.AudioSource.VOICE_DOWNLINK, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size) }
        for (a in attempts) {
            val r = runCatching { a() }.getOrNull() ?: continue
            if (r.state == AudioRecord.STATE_INITIALIZED) {
                runCatching { r.startRecording() }
                if (r.recordingState == AudioRecord.RECORDSTATE_RECORDING) return r
            }
            r.release()
        }
        return null
    }

    override fun startCapture(sampleRate: Int): ParcelFileDescriptor? {
        stopCapture()
        val rec = openRecorder(sampleRate) ?: return null
        val pipe = ParcelFileDescriptor.createPipe()
        capturing = true
        captureThread = Thread {
            val buf = ByteArray(3200)
            try {
                FileOutputStream(pipe[1].fileDescriptor).use { out ->
                    while (capturing) {
                        val n = rec.read(buf, 0, buf.size)
                        if (n < 0) break
                        if (n > 0) out.write(buf, 0, n)
                    }
                }
            } catch (_: Exception) {
            } finally {
                runCatching { rec.stop() }
                rec.release()
                runCatching { pipe[1].close() }
                capturing = false
            }
        }.apply { name = "autoagent-capture"; start() }
        return pipe[0]
    }

    override fun stopCapture() {
        capturing = false
        captureThread?.join(1500)
        captureThread = null
    }

    override fun enableAccessibility(component: String?): String {
        if (component.isNullOrBlank()) return "no component"
        return try {
            val current = exec("settings get secure enabled_accessibility_services").trim().takeUnless { it == "null" }.orEmpty()
            val list = current.split(':').filter { it.isNotBlank() }.toMutableList()
            if (component !in list) list += component
            exec("settings put secure enabled_accessibility_services ${list.joinToString(":")}")
            exec("settings put secure accessibility_enabled 1")
            ""
        } catch (e: Exception) {
            "settings: ${e.message}"
        }
    }

    private fun exec(cmd: String): String {
        val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        return out
    }
}
