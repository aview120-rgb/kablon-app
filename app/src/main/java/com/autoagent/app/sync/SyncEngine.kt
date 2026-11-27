package com.autoagent.app.sync

import android.content.Context
import com.autoagent.app.AutoAgentApp
import com.autoagent.app.BuildConfig
import com.autoagent.app.access.AccessibilityHealth
import com.autoagent.app.data.SecretBox
import com.autoagent.app.status.BatteryMonitor
import com.autoagent.app.status.NetworkMonitor
import com.autoagent.app.util.L
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Two-way sync with the task-queue server. One call uploads everything collected offline (outbox, outcomes,
 * receipts of stored tasks) and downloads everything the server prepared meanwhile. Every item is
 * acknowledged by id, so a dropped connection at any point only causes a harmless re-send.
 */
object SyncEngine {
    enum class Phase { DISABLED, NOT_CONFIGURED, OFFLINE, CONNECTING, CONNECTED, ERROR, AUTH_ERROR }

    data class ServerState(
        val phase: Phase = Phase.DISABLED,
        val lastSyncAt: Long = 0,
        val lastError: String = "",
        val lastErrorAt: Long = 0,
        val failures: Int = 0,
        val lastReceived: Int = 0,
        val lastUploaded: Int = 0,
        val live: Boolean = false,
    ) {
        val connected: Boolean get() = phase == Phase.CONNECTED

        fun label(): String = when (phase) {
            Phase.DISABLED -> L.t("Sync off", "סנכרון כבוי")
            Phase.NOT_CONFIGURED -> L.t("Not set up", "לא הוגדר")
            Phase.OFFLINE -> L.t("Offline — queued", "לא מקוון — בתור")
            Phase.CONNECTING -> L.t("Connecting…", "מתחבר…")
            Phase.CONNECTED -> if (live) L.t("Connected (live)", "מחובר (חי)") else L.t("Connected", "מחובר")
            Phase.ERROR -> L.t("Error — retrying", "שגיאה — מנסה שוב")
            Phase.AUTH_ERROR -> L.t("Wrong token", "אסימון שגוי")
        }
    }

    private val _state = MutableStateFlow(ServerState())
    val state: StateFlow<ServerState> = _state.asStateFlow()

    private val mutex = Mutex()
    private val again = AtomicBoolean(false)
    /** Cancellations applied locally; confirmed to the server in the next round. */
    private val cancelAcks = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    internal fun setLive(on: Boolean) {
        _state.value = _state.value.copy(live = on)
    }

    /** Settings → API client, or null with the state updated. */
    fun api(context: Context): SyncApi? {
        val app = context.applicationContext as AutoAgentApp
        val s = app.settings.current
        if (!s.syncEnabled) {
            _state.value = _state.value.copy(phase = Phase.DISABLED)
            return null
        }
        val token = app.settings.encryptedServerToken()?.let { SecretBox.decrypt(it) }
        if (s.serverUrl.isBlank() || token.isNullOrBlank()) {
            _state.value = _state.value.copy(phase = Phase.NOT_CONFIGURED)
            return null
        }
        return try {
            SyncApi(s.serverUrl, token)
        } catch (e: SyncException) {
            fail(e)
            null
        }
    }

    /**
     * Runs one sync (several rounds if there is more). If a sync is already running, it runs again right after.
     * Returns true on success. Never throws.
     */
    suspend fun syncOnce(context: Context, reason: String): Boolean {
        if (!mutex.tryLock()) {
            again.set(true)
            return true
        }
        try {
            var ok: Boolean
            do {
                again.set(false)
                ok = runRounds(context, reason)
            } while (ok && again.get())
            return ok
        } finally {
            mutex.unlock()
        }
    }

    private suspend fun runRounds(context: Context, reason: String): Boolean {
        val app = context.applicationContext as AutoAgentApp
        val api = api(app) ?: return false
        val net = NetworkMonitor.state.value
        if (!net.online) {
            _state.value = _state.value.copy(phase = Phase.OFFLINE)
            return false
        }
        if (_state.value.phase != Phase.CONNECTED) _state.value = _state.value.copy(phase = Phase.CONNECTING)
        val deviceId = app.settings.ensureDeviceId()
        var received = 0
        var uploaded = 0
        try {
            for (round in 0 until 10) {
                val outbox = app.db.outbox().batch(200)
                val acks = app.db.remoteTasks().unacked()
                val cancelled = synchronized(cancelAcks) { cancelAcks.toList() }
                val results = app.db.remoteTasks().unreported().map {
                    TaskResult(it.id, SyncProtocol.remoteStatus(it.state), it.note, it.updatedAt)
                }
                val req = SyncRequest(
                    deviceId = deviceId,
                    deviceName = app.settings.current.deviceName.ifBlank { android.os.Build.MODEL },
                    appVersion = BuildConfig.VERSION_NAME,
                    now = System.currentTimeMillis(),
                    status = statusMap(app),
                    received = acks + cancelled,
                    results = results,
                    uploads = outbox.map { Upload(it.clientId, it.kind, it.dataJson, it.at) },
                )
                if (outbox.isNotEmpty()) app.db.outbox().bumpAttempts(outbox.map { it.id })
                val resp = withContext(Dispatchers.IO) { SyncProtocol.decode(api.sync(SyncProtocol.encode(req))) }

                // Everything the server confirmed can be forgotten locally.
                if (resp.acceptedUploads.isNotEmpty()) app.db.outbox().deleteAccepted(resp.acceptedUploads)
                if (acks.isNotEmpty()) app.db.remoteTasks().markAcked(acks)
                if (resp.acceptedResults.isNotEmpty()) app.db.remoteTasks().markReported(resp.acceptedResults)
                cancelAcks.removeAll(cancelled.toSet())
                uploaded += resp.acceptedUploads.size + resp.acceptedResults.size

                val localNow = System.currentTimeMillis()
                for (t in resp.tasks) {
                    if (RemoteTaskExecutor.accept(app, t, resp.serverTime, localNow)) received++
                }
                for (id in resp.cancelled) {
                    RemoteTaskExecutor.cancel(app, id)
                    cancelAcks += id
                }
                val moreToSend = app.db.remoteTasks().unacked().isNotEmpty() || cancelAcks.isNotEmpty() ||
                    app.db.remoteTasks().unreported().isNotEmpty() || app.db.outbox().count() > 0
                if (!resp.hasMore && !moreToSend) break
            }
            _state.value = _state.value.copy(
                phase = Phase.CONNECTED, lastSyncAt = System.currentTimeMillis(), failures = 0,
                lastReceived = received, lastUploaded = uploaded,
            )
            if (received > 0 || uploaded > 0) {
                app.log("sync", L.t("Synced ($reason): received $received, uploaded $uploaded", "סונכרן ($reason): התקבלו $received, נשלחו $uploaded"), true)
            }
            app.db.remoteTasks().pruneReported(System.currentTimeMillis() - 14L * 86_400_000L)
            return true
        } catch (e: SyncException) {
            fail(e)
            return false
        } catch (e: Exception) {
            fail(SyncException(SyncException.Kind.CLIENT, L.t("Bad server reply", "תשובה לא תקינה מהשרת") + ": ${e.message}"))
            return false
        }
    }

    private fun fail(e: SyncException) {
        val prev = _state.value
        val phase = when (e.kind) {
            SyncException.Kind.AUTH -> Phase.AUTH_ERROR
            SyncException.Kind.NETWORK -> if (NetworkMonitor.state.value.online) Phase.ERROR else Phase.OFFLINE
            else -> Phase.ERROR
        }
        _state.value = prev.copy(phase = phase, lastError = e.message ?: "", lastErrorAt = System.currentTimeMillis(), failures = prev.failures + 1)
        // Log the first failure of a streak only, so the log does not fill up while offline.
        if (prev.failures == 0) AutoAgentApp.instance.log("sync", L.t("Sync failed: ${e.message}", "הסנכרון נכשל: ${e.message}"), false)
    }

    fun lastErrorRetryable(): Boolean = _state.value.phase == Phase.ERROR || _state.value.phase == Phase.OFFLINE

    private suspend fun statusMap(app: AutoAgentApp): Map<String, Any?> {
        val b = BatteryMonitor.read(app)
        return mapOf(
            "battery" to b.percent,
            "charging" to b.charging,
            "powerSave" to b.powerSave,
            "network" to NetworkMonitor.state.value.code(),
            "accessibility" to AccessibilityHealth.refresh(app).code(),
            "pendingSends" to app.db.sends().pending().size,
            "outbox" to app.db.outbox().count(),
        )
    }
}
