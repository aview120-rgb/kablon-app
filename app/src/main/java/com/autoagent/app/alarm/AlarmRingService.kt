package com.autoagent.app.alarm

import com.autoagent.app.util.L
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.autoagent.app.AutoAgentApp
import com.autoagent.app.R
import com.autoagent.app.data.AlarmEntity
import com.autoagent.app.util.Notifier
import kotlinx.coroutines.launch

/** Plays the alarm sound with a full-screen notification until dismissed or snoozed. */
class AlarmRingService : Service() {
    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var label: String = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISMISS -> { stopRinging(); return START_NOT_STICKY }
            ACTION_SNOOZE -> {
                val app = applicationContext as AutoAgentApp
                val lbl = label.ifBlank { intent.getStringExtra(EXTRA_LABEL) ?: L.t("Alarm", "שעון מעורר") }
                app.appScope.launch {
                    val at = System.currentTimeMillis() + SNOOZE_MS
                    val id = app.db.alarms().insert(AlarmEntity(triggerAt = at, label = L.t("$lbl (snoozed)", "$lbl (נודניק)")))
                    AlarmScheduler.schedule(app, AlarmEntity(id = id, triggerAt = at, label = L.t("$lbl (snoozed)", "$lbl (נודניק)")))
                }
                stopRinging()
                return START_NOT_STICKY
            }
        }
        label = intent?.getStringExtra(EXTRA_LABEL) ?: L.t("Alarm", "שעון מעורר")
        val fullScreen = PendingIntent.getActivity(
            this, 1,
            Intent(this, AlarmActivity::class.java).putExtra(EXTRA_LABEL, label)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, Notifier.CH_ALARM)
            .setSmallIcon(R.drawable.ic_stat_agent)
            .setContentTitle(L.t("Alarm", "שעון מעורר"))
            .setContentText(label)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setFullScreenIntent(fullScreen, true)
            .setContentIntent(fullScreen)
            .setOngoing(true)
            .addAction(0, L.t("Snooze 10 min", "נודניק 10 דק'"), actionIntent(ACTION_SNOOZE, 2))
            .addAction(0, L.t("Dismiss", "כיבוי"), actionIntent(ACTION_DISMISS, 3))
            .build()
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
        ServiceCompat.startForeground(this, Notifier.ID_ALARM_RING, notification, type)
        startRinging()
        return START_NOT_STICKY
    }

    private fun actionIntent(action: String, code: Int): PendingIntent =
        PendingIntent.getService(
            this, code, Intent(this, AlarmRingService::class.java).setAction(action).putExtra(EXTRA_LABEL, label),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun startRinging() {
        if (player != null) return
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        player = runCatching {
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
                )
                setDataSource(this@AlarmRingService, uri)
                isLooping = true
                prepare()
                start()
            }
        }.getOrNull()
        @Suppress("DEPRECATION")
        vibrator = getSystemService(Vibrator::class.java)
        vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 600), 0))
    }

    private fun stopRinging() {
        player?.runCatching { stop(); release() }
        player = null
        vibrator?.cancel()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
        sendBroadcast(Intent(ACTION_FINISHED).setPackage(packageName))
    }

    override fun onDestroy() {
        player?.runCatching { stop(); release() }
        vibrator?.cancel()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_LABEL = "label"
        const val ACTION_DISMISS = "com.autoagent.app.ALARM_DISMISS"
        const val ACTION_SNOOZE = "com.autoagent.app.ALARM_SNOOZE"
        const val ACTION_FINISHED = "com.autoagent.app.ALARM_FINISHED"
        const val SNOOZE_MS = 10 * 60_000L
    }
}
