package com.autoagent.app.util

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.autoagent.app.MainActivity
import com.autoagent.app.R

object Notifier {
    const val CH_STATUS = "status"
    const val CH_EVENTS = "events"
    const val CH_ALARM = "alarm"
    const val CH_CALL = "call"

    const val ID_AGENT = 1
    const val ID_ALARM_RING = 2
    const val ID_INCOMING_CALL = 3
    const val ID_SEND_WORK = 4

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_STATUS, L.t("Agent status (persistent)", "מצב הסוכן (קבוע)"), NotificationManager.IMPORTANCE_LOW).apply {
                description = L.t("Shown while AutoAgent is listening or waiting for a schedule", "מוצג כש-AutoAgent מקשיב או ממתין לתזמון")
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_EVENTS, L.t("Automation results", "תוצאות אוטומציה"), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = L.t("Every automated action (sent, aborted, answered) is reported here", "כל פעולה אוטומטית (נשלח, בוטל, נענה) מדווחת כאן")
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ALARM, L.t("Alarms", "שעונים מעוררים"), NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null)
                enableVibration(true)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_CALL, L.t("Calls", "שיחות"), NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null)
            }
        )
    }

    fun openAppIntent(context: Context, route: String? = null): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (route != null) putExtra(MainActivity.EXTRA_ROUTE, route)
        }
        return PendingIntent.getActivity(
            context, route?.hashCode() ?: 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Visible report for an automated action. Automation is never hidden. */
    fun event(context: Context, title: String, text: String, route: String? = null) {
        if (!canPost(context)) return
        val n = NotificationCompat.Builder(context, CH_EVENTS)
            .setSmallIcon(R.drawable.ic_stat_agent)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openAppIntent(context, route))
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify((System.nanoTime() and 0x7fffffff).toInt(), n)
        } catch (_: SecurityException) {
        }
    }
}
