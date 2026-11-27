package com.autoagent.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.autoagent.app.AutoAgentApp
import com.autoagent.app.access.AccessibilityHealth
import com.autoagent.app.access.ScreenActions
import com.autoagent.app.data.ActionLog
import com.autoagent.app.data.RemoteTask
import com.autoagent.app.data.SecretBox
import com.autoagent.app.sync.Outbox
import com.autoagent.app.sync.SyncApi
import com.autoagent.app.sync.SyncEngine
import com.autoagent.app.sync.SyncException
import com.autoagent.app.sync.SyncScheduler
import com.autoagent.app.util.L
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Calendar

class SyncViewModel(application: Application) : AndroidViewModel(application) {
    private val app = getApplication<AutoAgentApp>()
    val settings = app.settings.state
    val server = SyncEngine.state
    val health = AccessibilityHealth.health
    val queue = ScreenActions.get(app).state
    val message = MutableStateFlow<String?>(null)
    val busy = MutableStateFlow(false)

    val tasks: StateFlow<List<RemoteTask>> = app.db.remoteTasks().observeRecent()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val outboxCount: StateFlow<Int> = app.db.outbox().observeCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    val logs: StateFlow<List<ActionLog>> = app.db.logs().observeRecent()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        app.settings.ensureDeviceId()
    }

    fun save(url: String, token: String, deviceName: String) {
        val u = url.trim()
        if (u.isNotEmpty() && !u.startsWith("http://") && !u.startsWith("https://")) {
            message.value = L.t("Address must start with http:// or https://", "הכתובת חייבת להתחיל ב-http:// או https://")
            return
        }
        app.settings.setServer(u, deviceName, token.trim().takeIf { it.isNotEmpty() }?.let { SecretBox.encrypt(it) })
        message.value = L.t("Saved", "נשמר")
        app.log("sync", L.t("Server settings saved", "הגדרות השרת נשמרו"), true)
        SyncScheduler.ensurePeriodic(app)
        SyncScheduler.syncNow(app, "settings")
    }

    fun setEnabled(on: Boolean) {
        app.settings.setSyncEnabled(on)
        SyncScheduler.ensurePeriodic(app)
        if (on) SyncScheduler.syncNow(app, "enabled") else SyncEngine.api(app)
    }

    fun setLive(on: Boolean) = app.settings.setLiveConnection(on)
    fun setUploadInbox(on: Boolean) = app.settings.setUploadInbox(on)
    fun setRemoteAutoSend(on: Boolean) = app.settings.setRemoteAutoSend(on)

    fun syncNow() {
        viewModelScope.launch {
            busy.value = true
            val ok = SyncEngine.syncOnce(app, L.t("manual", "ידני"))
            busy.value = false
            val s = SyncEngine.state.value
            message.value = if (ok && s.connected) L.t("Synced: received ${s.lastReceived}, uploaded ${s.lastUploaded}", "סונכרן: התקבלו ${s.lastReceived}, נשלחו ${s.lastUploaded}")
            else s.lastError.ifBlank { s.label() }
        }
    }

    fun testConnection() {
        viewModelScope.launch {
            busy.value = true
            message.value = try {
                val api = SyncEngine.api(app) ?: throw SyncException(SyncException.Kind.CONFIG, SyncEngine.state.value.label())
                withContext(Dispatchers.IO) { api.health() }
                L.t("Server reachable ✓", "השרת זמין ✓")
            } catch (e: SyncException) {
                e.message
            }
            busy.value = false
        }
    }

    fun recoverAccessibility() {
        message.value = AccessibilityHealth.recover(app, force = true)
    }

    /** Created on the phone and queued offline if needed; the server builds the summary every morning, even while the phone is off. */
    fun requestDailyDigest() {
        viewModelScope.launch {
            if (!app.settings.current.syncEnabled) {
                message.value = L.t("Turn on sync first", "צריך להפעיל סנכרון קודם")
                return@launch
            }
            val c = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 8); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_MONTH, 1)
            }
            Outbox.addServerTask(app, JSONObject()
                .put("type", "digest")
                .put("title", L.t("Daily inbox summary", "סיכום הודעות יומי"))
                .put("runAt", c.timeInMillis)
                .put("payload", JSONObject().put("hours", 24))
                .put("repeat", JSONObject().put("everyMinutes", 24 * 60)))
            if (!app.settings.current.uploadInbox) app.settings.setUploadInbox(true)
            message.value = L.t("Daily summary requested (sent to the server when online)", "התבקש סיכום יומי (יישלח לשרת כשיהיה חיבור)")
        }
    }
}
