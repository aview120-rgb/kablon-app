package com.kablon.automation

import android.app.Application
import com.kablon.automation.core.Notifier
import com.kablon.automation.core.WorkflowLog

class KablonApp : Application() {
    override fun onCreate() {
        super.onCreate()
        WorkflowLog.init(this)
        Notifier.createChannels(this)
    }
}
