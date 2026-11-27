package com.autoagent.app.sync

import com.autoagent.app.AutoAgentApp
import com.autoagent.app.status.NetworkMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * Keeps a live connection while the app process runs: long-polls the server, syncs as soon as it reports
 * something, and reconnects automatically with exponential backoff after any error. Pauses while offline;
 * resumes (with an immediate sync) when the network returns.
 */
object LiveSync {
    private var started = false

    fun start(app: AutoAgentApp) {
        if (started) return
        started = true
        NetworkMonitor.onBecameOnline { SyncScheduler.syncNow(app, "network") }
        app.appScope.launch {
            combine(app.settings.state, NetworkMonitor.state) { s, n ->
                Triple(s.syncEnabled && s.liveConnection, n.online, s.serverUrl + s.serverTokenSet)
            }.distinctUntilChanged().collectLatest { (enabled, online, _) ->
                SyncEngine.setLive(false)
                if (enabled && online) loop(app)
            }
        }
    }

    private suspend fun loop(app: AutoAgentApp) {
        val backoff = Backoff(baseMs = 2_000, maxMs = 5 * 60_000L)
        var lastFull = 0L
        while (coroutineContext.isActive) {
            val api = SyncEngine.api(app) ?: return
            val needFull = System.currentTimeMillis() - lastFull > 5 * 60_000L
            if (needFull) {
                if (!SyncEngine.syncOnce(app, "live")) {
                    SyncEngine.setLive(false)
                    if (SyncEngine.state.value.phase == SyncEngine.Phase.AUTH_ERROR) return // wait for new settings
                    delay(backoff.next())
                    continue
                }
                lastFull = System.currentTimeMillis()
            }
            try {
                SyncEngine.setLive(true)
                val changed = withContext(Dispatchers.IO) { api.waitForChanges(app.settings.ensureDeviceId(), 25) }
                backoff.reset()
                if (changed) {
                    SyncEngine.syncOnce(app, "push")
                    lastFull = System.currentTimeMillis()
                }
            } catch (e: SyncException) {
                SyncEngine.setLive(false)
                if (e.kind == SyncException.Kind.AUTH) {
                    SyncEngine.syncOnce(app, "auth-check")
                    return
                }
                delay(backoff.next())
            }
        }
    }
}
