package com.autoagent.app.priv

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import com.autoagent.app.AutoAgentApp
import com.autoagent.app.BuildConfig
import com.autoagent.app.access.AutomationService
import com.autoagent.app.util.L
import com.autoagent.app.voice.Speaker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Talks to [PrivService] through Shizuku. When connected and the phone exposes an in-call output, the assistant
 * speaks straight into the call and hears the line; otherwise callers fall back to speakerphone.
 */
object CallBridge {
    const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    private const val REQUEST_CODE = 4711

    data class State(
        val shizukuInstalled: Boolean = false,
        val shizukuRunning: Boolean = false,
        val permission: Boolean = false,
        val connected: Boolean = false,
        val uplink: Boolean = false,
        val probe: String = "",
        val error: String = "",
    ) {
        val ready: Boolean get() = connected && uplink
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile private var service: IPrivService? = null
    private lateinit var app: Context
    private var initialized = false

    private val args by lazy {
        Shizuku.UserServiceArgs(ComponentName(BuildConfig.APPLICATION_ID, PrivService::class.java.name))
            .daemon(false)
            .processNameSuffix("callaudio")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder == null || !binder.pingBinder()) return
            val s = IPrivService.Stub.asInterface(binder)
            service = s
            val probe = runCatching { s.probe() }.getOrDefault("")
            _state.value = _state.value.copy(connected = true, probe = probe, uplink = probe.contains("uplink=true"), error = "")
            if (!AutomationService.isEnabled(app)) {
                runCatching { s.enableAccessibility(ComponentName(app, AutomationService::class.java).flattenToString()) }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            _state.value = _state.value.copy(connected = false)
        }
    }

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        app = context.applicationContext
        Shizuku.addBinderReceivedListenerSticky { refresh() }
        Shizuku.addBinderDeadListener { service = null; refresh() }
        Shizuku.addRequestPermissionResultListener { _, _ -> refresh() }
        refresh()
    }

    fun isShizukuInstalled(context: Context): Boolean =
        runCatching { context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0) }.isSuccess

    fun refresh() {
        if (!initialized) return
        val installed = isShizukuInstalled(app)
        val running = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        val perm = running && runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)
        _state.value = _state.value.copy(shizukuInstalled = installed, shizukuRunning = running, permission = perm,
            connected = service != null && perm)
        val enabled = (app as? AutoAgentApp)?.settings?.current?.useCallBridge ?: true
        if (perm && service == null && enabled) {
            runCatching { Shizuku.bindUserService(args, connection) }
                .onFailure { _state.value = _state.value.copy(error = it.message ?: it.javaClass.simpleName) }
        }
    }

    fun requestPermission() {
        runCatching { Shizuku.requestPermission(REQUEST_CODE) }
            .onFailure { _state.value = _state.value.copy(error = it.message ?: "") }
    }

    fun disconnect() {
        runCatching { Shizuku.unbindUserService(args, connection, true) }
        service = null
        _state.value = _state.value.copy(connected = false)
    }

    /**
     * Restarts (or switches on) the accessibility service as the shell user, without root.
     * null = Shizuku is not connected; "" = done; otherwise the error.
     */
    fun restartAccessibility(component: String): String? {
        val s = service ?: return null
        return runCatching { s.restartAccessibility(component) }.getOrElse { it.message ?: it.javaClass.simpleName }
    }

    fun installIntent(): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$SHIZUKU_PACKAGE")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun openShizukuIntent(context: Context): Intent? = context.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)

    /** True when speech can go straight into the call. */
    fun canSpeakIntoCall(): Boolean {
        val enabled = (app as? AutoAgentApp)?.settings?.current?.useCallBridge ?: false
        return initialized && enabled && service != null && _state.value.uplink
    }

    fun canHearCall(): Boolean = initialized && service != null && Build.VERSION.SDK_INT >= 33

    // ---------------- speaking ----------------

    private data class Pcm(val data: ByteArray, val sampleRate: Int)

    /** Reads a PCM16 WAV file produced by TextToSpeech, down-mixing to mono. */
    private fun readWav(f: File): Pcm? {
        val bytes = f.readBytes()
        if (bytes.size < 44) return null
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 12
        var channels = 1
        var rate = 16_000
        var bits = 16
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4, Charsets.US_ASCII)
            val len = bb.getInt(pos + 4)
            if (id == "fmt ") {
                channels = bb.getShort(pos + 10).toInt()
                rate = bb.getInt(pos + 12)
                bits = bb.getShort(pos + 22).toInt()
            } else if (id == "data") {
                if (bits != 16) return null
                val start = pos + 8
                val end = minOf(bytes.size, start + if (len <= 0) bytes.size else len)
                val raw = bytes.copyOfRange(start, end)
                if (channels <= 1) return Pcm(raw, rate)
                val frames = raw.size / (2 * channels)
                val mono = ByteArray(frames * 2)
                val rb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
                for (i in 0 until frames) {
                    var sum = 0
                    for (c in 0 until channels) sum += rb.getShort((i * channels + c) * 2)
                    val v = sum / channels
                    mono[i * 2] = (v and 0xff).toByte(); mono[i * 2 + 1] = ((v shr 8) and 0xff).toByte()
                }
                return Pcm(mono, rate)
            }
            pos += 8 + len + (len and 1)
        }
        return null
    }

    /** Synthesizes [text] and plays it into the call. Returns "" on success or an error message. */
    suspend fun speakIntoCall(speaker: Speaker, text: String): String {
        val s = service ?: return "not connected"
        val f = File(app.cacheDir, "say_${System.nanoTime()}.wav")
        try {
            if (!speaker.synthesizeToFile(text, f)) return "TTS failed"
            val pcm = withContext(Dispatchers.IO) { readWav(f) } ?: return "TTS produced no PCM"
            return withContext(Dispatchers.IO) {
                val pipe = ParcelFileDescriptor.createPipe()
                val writer = Thread {
                    runCatching { FileOutputStream(pipe[1].fileDescriptor).use { it.write(pcm.data) } }
                    runCatching { pipe[1].close() }
                }.apply { start() }
                val r = runCatching { s.play(pipe[0], pcm.sampleRate) }.getOrElse { it.message ?: "remote error" }
                runCatching { pipe[0].close() }
                writer.join(2000)
                r
            }
        } finally {
            f.delete()
        }
    }

    // ---------------- hearing ----------------

    /**
     * Captures the call (both sides) into [wavOut] and lets the speech recognizer read the same audio through
     * pipes created by [newListenPipe]. Call [stop] when the call ends.
     */
    class Capture internal constructor(private val src: ParcelFileDescriptor, private val wavOut: File, val sampleRate: Int) {
        @Volatile private var running = true
        @Volatile private var sink: ParcelFileDescriptor? = null
        @Volatile private var sinkOut: FileOutputStream? = null
        private var bytes = 0L
        private val thread = Thread {
            val buf = ByteArray(3200)
            RandomAccessFile(wavOut, "rw").use { raf ->
                raf.setLength(0)
                raf.write(ByteArray(44))
                FileInputStream(src.fileDescriptor).use { input ->
                    while (running) {
                        val n = runCatching { input.read(buf) }.getOrDefault(-1)
                        if (n < 0) break
                        if (n == 0) continue
                        raf.write(buf, 0, n)
                        bytes += n
                        sinkOut?.let { out -> if (runCatching { out.write(buf, 0, n) }.isFailure) closeSink() }
                    }
                }
                writeHeader(raf)
            }
        }.apply { name = "autoagent-callhear"; start() }

        private fun writeHeader(raf: RandomAccessFile) {
            val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            h.put("RIFF".toByteArray()); h.putInt((36 + bytes).toInt()); h.put("WAVE".toByteArray())
            h.put("fmt ".toByteArray()); h.putInt(16); h.putShort(1); h.putShort(1); h.putInt(sampleRate)
            h.putInt(sampleRate * 2); h.putShort(2); h.putShort(16)
            h.put("data".toByteArray()); h.putInt(bytes.toInt())
            raf.seek(0); raf.write(h.array())
        }

        /** Returns the read end of a fresh pipe that receives the live call audio until [closeSink]. */
        fun newListenPipe(): ParcelFileDescriptor {
            closeSink()
            val p = ParcelFileDescriptor.createPipe()
            sink = p[1]
            sinkOut = FileOutputStream(p[1].fileDescriptor)
            return p[0]
        }

        fun closeSink() {
            runCatching { sinkOut?.close() }
            runCatching { sink?.close() }
            sinkOut = null; sink = null
        }

        /** Seconds of audio captured so far. */
        val seconds: Long get() = bytes / (sampleRate * 2L)

        fun stop() {
            running = false
            runCatching { service?.stopCapture() }
            closeSink()
            thread.join(2000)
            runCatching { src.close() }
        }
    }

    suspend fun startCapture(wavOut: File, sampleRate: Int = 16_000): Capture? = withContext(Dispatchers.IO) {
        val s = service ?: return@withContext null
        val pfd = runCatching { s.startCapture(sampleRate) }.getOrNull() ?: return@withContext null
        Capture(pfd, wavOut, sampleRate)
    }

    fun statusText(): String {
        val st = _state.value
        return when {
            !st.shizukuInstalled -> L.t("Shizuku is not installed", "Shizuku לא מותקן")
            !st.shizukuRunning -> L.t("Shizuku is installed but not started", "Shizuku מותקן אבל לא הופעל")
            !st.permission -> L.t("AutoAgent has not been allowed in Shizuku", "עוד לא אישרת את AutoAgent בתוך Shizuku")
            !st.connected -> L.t("Connecting…", "מתחבר…") + if (st.error.isNotBlank()) " (${st.error})" else ""
            !st.uplink -> L.t(
                "Connected, but this phone exposes no in-call audio output; using speakerphone",
                "מחובר, אבל הטלפון לא חושף יציאת קול לתוך השיחה; משתמש ברמקול",
            )
            else -> L.t("Ready: the assistant speaks directly into calls", "מוכן: העוזר מדבר ישירות לתוך השיחות")
        }
    }
}
