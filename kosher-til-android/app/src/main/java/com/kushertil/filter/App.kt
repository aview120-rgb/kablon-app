package com.kushertil.filter

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.kushertil.filter.data.Blocklist
import com.kushertil.filter.data.Prefs

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        Blocklist.load(this)

        // Seed the default server URL on first run if the build ships one.
        if (Prefs.serverUrl(this).isBlank() && BuildConfig.DEFAULT_SERVER_URL.isNotBlank()) {
            Prefs.setServerUrl(this, BuildConfig.DEFAULT_SERVER_URL)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel),
                NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_ID = "filter_status"
    }
}
