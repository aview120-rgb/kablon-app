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
    /** "auto" (follow phone), "he" or "en". */
    val language: String = "auto",
    /** Use Google's online recognizer when the offline pack for the language is missing. */
    val allowOnlineSpeech: Boolean = true,
    /** Use the Shizuku in-call audio bridge (speak into the call / hear the line) when available. */
    val useCallBridge: Boolean = true,
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
        language = prefs.getString(K_LANG, "auto") ?: "auto",
        allowOnlineSpeech = prefs.getBoolean(K_ONLINE, true),
        useCallBridge = prefs.getBoolean(K_BRIDGE, true),
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
    }
}
