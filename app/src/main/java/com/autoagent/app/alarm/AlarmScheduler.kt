package com.autoagent.app.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.autoagent.app.MainActivity
import com.autoagent.app.data.AlarmEntity

object AlarmScheduler {
    const val EXTRA_ALARM_ID = "alarm_id"

    fun canScheduleExact(context: Context): Boolean {
        val am = context.getSystemService(AlarmManager::class.java)
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
    }

    private fun firePending(context: Context, id: Long): PendingIntent =
        PendingIntent.getBroadcast(
            context, id.toInt(),
            Intent(context, AlarmReceiver::class.java).setAction(AlarmReceiver.ACTION_FIRE).putExtra(EXTRA_ALARM_ID, id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /** Uses setAlarmClock: exact, shown in the status bar, allowed in Doze. Returns false if exact alarms are not permitted. */
    fun schedule(context: Context, alarm: AlarmEntity): Boolean {
        if (!canScheduleExact(context)) return false
        val am = context.getSystemService(AlarmManager::class.java)
        val show = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_ROUTE, "schedules"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return try {
            am.setAlarmClock(AlarmManager.AlarmClockInfo(alarm.triggerAt, show), firePending(context, alarm.id))
            true
        } catch (_: SecurityException) {
            false
        }
    }

    fun cancel(context: Context, id: Long) {
        context.getSystemService(AlarmManager::class.java).cancel(firePending(context, id))
    }
}
