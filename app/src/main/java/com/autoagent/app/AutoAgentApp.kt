package com.autoagent.app

import android.app.Application
import com.autoagent.app.calls.PhoneAccounts
import com.autoagent.app.data.AppDatabase
import com.autoagent.app.data.ActionLog
import com.autoagent.app.data.Settings
import com.autoagent.app.schedule.WatchdogWorker
import com.autoagent.app.service.AgentStatus
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
        Notifier.createChannels(this)
        PhoneAccounts.register(this)
        WatchdogWorker.ensureScheduled(this)
        appScope.launch {
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
