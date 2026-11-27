package com.autoagent.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SettingsState(
    val autoAnswerEnabled: Boolean = false,
    val autoAnswerDelaySec: Int = 6,
    val maxTurns: Int = 3,
    val imapHost: String = "",
    val imapPort: Int = 993,
    val imapUser: String = "",
    val imapPasswordSet: Boolean = false,
    val seeded: Boolean = false,
    /** "he" (default, full right-to-left Hebrew), "en" or "auto" (follow phone). */
    val language: String = "he",
    /** Use Google's online recognizer when the offline pack for the language is missing. */
    val allowOnlineSpeech: Boolean = true,
    /** Use the Shizuku in-call audio bridge (speak into the call / hear the line) when available. */
    val useCallBridge: Boolean = true,
    /** Task-queue server sync. */
    val syncEnabled: Boolean = false,
    val serverUrl: String = "",
    val serverTokenSet: Boolean = false,
    val deviceId: String = "",
    val deviceName: String = "",
    /** Upload notification text (inbox) so the server can build digests while the phone is off. Off by default. */
    val uploadInbox: Boolean = false,
    /** Let server tasks press Send by themselves. When off they are only prepared and you press Send. */
    val remoteAutoSend: Boolean = false,
    /** Keep a live (long-poll) connection while the app process runs, for instant delivery. */
    val liveConnection: Boolean = true,
)

/** Small SharedPreferences-backed settings store exposed as a StateFlow. Stays on device. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("autoagent_settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(read())
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    private fun read() = SettingsState(
        autoAnswerEnabled = prefs.getBoolean(K_AUTO, false),
        autoAnswerDelaySec = prefs.getInt(K_DELAY, 6),
        maxTurns = prefs.getInt(K_TURNS, 3),
        imapHost = prefs.getString(K_HOST, "") ?: "",
        imapPort = prefs.getInt(K_PORT, 993),
        imapUser = prefs.getString(K_USER, "") ?: "",
        imapPasswordSet = !prefs.getString(K_PASS, null).isNullOrEmpty(),
        seeded = prefs.getBoolean(K_SEEDED, false),
        language = prefs.getString(K_LANG, "he") ?: "he",
        allowOnlineSpeech = prefs.getBoolean(K_ONLINE, true),
        useCallBridge = prefs.getBoolean(K_BRIDGE, true),
        syncEnabled = prefs.getBoolean(K_SYNC, false),
        serverUrl = prefs.getString(K_URL, "") ?: "",
        serverTokenSet = !prefs.getString(K_TOKEN, null).isNullOrEmpty(),
        deviceId = prefs.getString(K_DEVICE, "") ?: "",
        deviceName = prefs.getString(K_DEVICE_NAME, "") ?: "",
        uploadInbox = prefs.getBoolean(K_UPLOAD_INBOX, false),
        remoteAutoSend = prefs.getBoolean(K_REMOTE_SEND, false),
        liveConnection = prefs.getBoolean(K_LIVE, true),
    )

    private fun edit(block: android.content.SharedPreferences.Editor.() -> Unit) {
        prefs.edit().apply(block).apply()
        _state.value = read()
    }

    val current: SettingsState get() = _state.value

    fun setAutoAnswer(enabled: Boolean) = edit { putBoolean(K_AUTO, enabled) }
    fun setAutoAnswerDelay(sec: Int) = edit { putInt(K_DELAY, sec.coerceIn(0, 30)) }
    fun setMaxTurns(turns: Int) = edit { putInt(K_TURNS, turns.coerceIn(1, 6)) }
    fun setAllowOnlineSpeech(on: Boolean) = edit { putBoolean(K_ONLINE, on) }
    fun setUseCallBridge(on: Boolean) = edit { putBoolean(K_BRIDGE, on) }
    fun setSeeded() = edit { putBoolean(K_SEEDED, true) }
    fun setLanguage(mode: String) {
        edit { putString(K_LANG, mode) }
        com.autoagent.app.util.L.apply(mode)
    }

    fun setImap(host: String, port: Int, user: String, encryptedPassword: String?) = edit {
        putString(K_HOST, host.trim())
        putInt(K_PORT, port)
        putString(K_USER, user.trim())
        if (encryptedPassword != null) putString(K_PASS, encryptedPassword)
    }

    fun clearImap() = edit { remove(K_HOST); remove(K_USER); remove(K_PASS); putInt(K_PORT, 993) }

    fun encryptedImapPassword(): String? = prefs.getString(K_PASS, null)

    /** Stable random id for this phone on the server (created once). */
    fun ensureDeviceId(): String {
        current.deviceId.takeIf { it.isNotBlank() }?.let { return it }
        val id = "android-" + java.util.UUID.randomUUID().toString().take(13)
        edit {
            putString(K_DEVICE, id)
            if (prefs.getString(K_DEVICE_NAME, "").isNullOrBlank()) putString(K_DEVICE_NAME, "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
        }
        return id
    }

    /** [encryptedToken] null keeps the saved token. */
    fun setServer(url: String, deviceName: String, encryptedToken: String?) = edit {
        putString(K_URL, url.trim().trimEnd('/'))
        putString(K_DEVICE_NAME, deviceName.trim())
        if (encryptedToken != null) putString(K_TOKEN, encryptedToken)
    }

    fun encryptedServerToken(): String? = prefs.getString(K_TOKEN, null)
    fun setSyncEnabled(on: Boolean) = edit { putBoolean(K_SYNC, on) }
    fun setUploadInbox(on: Boolean) = edit { putBoolean(K_UPLOAD_INBOX, on) }
    fun setRemoteAutoSend(on: Boolean) = edit { putBoolean(K_REMOTE_SEND, on) }
    fun setLiveConnection(on: Boolean) = edit { putBoolean(K_LIVE, on) }

    companion object {
        private const val K_AUTO = "auto_answer"
        private const val K_DELAY = "auto_answer_delay"
        private const val K_TURNS = "max_turns"
        private const val K_HOST = "imap_host"
        private const val K_PORT = "imap_port"
        private const val K_USER = "imap_user"
        private const val K_PASS = "imap_pass"
        private const val K_SEEDED = "seeded"
        private const val K_LANG = "language"
        private const val K_ONLINE = "online_speech"
        private const val K_BRIDGE = "call_bridge"
        private const val K_SYNC = "sync_enabled"
        private const val K_URL = "server_url"
        private const val K_TOKEN = "server_token"
        private const val K_DEVICE = "device_id"
        private const val K_DEVICE_NAME = "device_name"
        private const val K_UPLOAD_INBOX = "upload_inbox"
        private const val K_REMOTE_SEND = "remote_auto_send"
        private const val K_LIVE = "live_connection"
    }
}
