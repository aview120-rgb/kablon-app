package com.autoagent.app.service

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.autoagent.app.R
import com.autoagent.app.util.Notifier

/**
 * Persistent foreground service whose notification states exactly what AutoAgent is doing:
 * waiting for schedules, auto-answer armed, or listening to the microphone.
 */
class AgentForegroundService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val text = intent?.getStringExtra(EXTRA_TEXT) ?: "AutoAgent is active"
        val listening = intent?.getBooleanExtra(EXTRA_LISTENING, false) == true
        val n = NotificationCompat.Builder(this, Notifier.CH_STATUS)
            .setSmallIcon(R.drawable.ic_stat_agent)
            .setContentTitle(if (listening) "AutoAgent is listening (microphone on)" else "AutoAgent is waiting")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(Notifier.openAppIntent(this, "home"))
            .build()
        var type = 0
        if (Build.VERSION.SDK_INT >= 34) {
            type = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            if (listening) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else if (Build.VERSION.SDK_INT >= 30 && listening) {
            type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        try {
            ServiceCompat.startForeground(this, Notifier.ID_AGENT, n, type)
        } catch (e: Exception) {
            stopSelf()
        }
        return START_STICKY
    }

    companion object {
        const val EXTRA_TEXT = "text"
        const val EXTRA_LISTENING = "listening"
    }
}
