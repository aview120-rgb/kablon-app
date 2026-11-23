package com.kablon.automation.core

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.kablon.automation.R
import com.kablon.automation.ui.MainActivity

object Notifier {
    private const val CH_ALERT = "kablon_alert"
    private const val CH_INFO = "kablon_info"
    const val ID_MANUAL = 1001
    const val ID_DONE = 1002

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERT, "נדרשת התערבות ידנית", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "כשהאוטומציה נתקעת ומחכה לך"
                enableVibration(true)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_INFO, "סטטוס זרימות", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    fun canNotify(context: Context): Boolean =
        (Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()

    fun manualNeeded(context: Context, message: String) {
        vibrate(context)
        post(context, ID_MANUAL, CH_ALERT, "✋ Kablon צריך אותך", message)
    }

    fun info(context: Context, title: String, message: String) =
        post(context, ID_DONE, CH_INFO, title, message)

    fun cancelManual(context: Context) = NotificationManagerCompat.from(context).cancel(ID_MANUAL)

    @android.annotation.SuppressLint("MissingPermission")
    private fun post(context: Context, id: Int, channel: String, title: String, text: String) {
        if (!canNotify(context)) return
        val pi = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        NotificationManagerCompat.from(context).notify(id, n)
    }

    private fun vibrate(context: Context) {
        try {
            val v = context.getSystemService(Vibrator::class.java) ?: return
            v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 250, 150, 250), -1))
        } catch (_: Exception) {
        }
    }
}
