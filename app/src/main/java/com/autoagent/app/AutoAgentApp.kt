package com.autoagent.app

import android.app.Application
import com.autoagent.app.calls.PhoneAccounts
import com.autoagent.app.data.AppDatabase
import com.autoagent.app.data.ActionLog
import com.autoagent.app.data.Settings
import com.autoagent.app.schedule.WatchdogWorker
import com.autoagent.app.service.AgentStatus
import com.autoagent.app.util.L
import com.autoagent.app.util.Notifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AutoAgentApp : Application() {
    lateinit var db: AppDatabase
        private set
    lateinit var settings: Settings
        private set
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        instance = this
        db = AppDatabase.build(this)
        settings = Settings(this)
        L.apply(settings.current.language)
        Notifier.createChannels(this)
        PhoneAccounts.register(this)
        com.autoagent.app.priv.CallBridge.init(this)
        WatchdogWorker.ensureScheduled(this)
        // Live device status (battery, network, accessibility) and server sync with automatic reconnect.
        com.autoagent.app.status.NetworkMonitor.start(this)
        com.autoagent.app.status.BatteryMonitor.start(this)
        com.autoagent.app.access.AccessibilityHealth.refresh(this)
        com.autoagent.app.sync.LiveSync.start(this)
        com.autoagent.app.sync.SyncScheduler.ensurePeriodic(this)
        appScope.launch {
            com.autoagent.app.sync.RemoteTaskExecutor.resumeOpen(this@AutoAgentApp)
            if (!settings.current.seeded) {
                if (db.rules().count() == 0) db.rules().insertAll(AppDatabase.DEFAULT_RULES)
                settings.setSeeded()
            }
            AgentStatus.refresh(this@AutoAgentApp)
        }
    }

    fun log(kind: String, message: String, ok: Boolean) {
        appScope.launch { db.logs().insert(ActionLog(kind = kind, message = message, ok = ok)) }
    }

    companion object {
        lateinit var instance: AutoAgentApp
            private set
    }
}
