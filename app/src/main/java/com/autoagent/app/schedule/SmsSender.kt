package com.autoagent.app.schedule

import com.autoagent.app.util.L
import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import com.autoagent.app.AutoAgentApp
import com.autoagent.app.data.SendStatus
import com.autoagent.app.service.AgentStatus
import com.autoagent.app.util.Notifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object SmsSender {
    const val ACTION_SENT = "com.autoagent.app.SMS_SENT"

    fun hasPermission(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED

    /** Hands the text to SmsManager. The final result arrives in [SmsResultReceiver]. */
    fun send(context: Context, sendId: Long, number: String, text: String) {
        val sms: SmsManager = if (Build.VERSION.SDK_INT >= 31) {
            context.getSystemService(SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        }
        val parts = sms.divideMessage(text)
        val sentPi = PendingIntent.getBroadcast(
            context, (200_000 + sendId).toInt(),
            Intent(context, SmsResultReceiver::class.java).setAction(ACTION_SENT).putExtra(SendScheduler.EXTRA_SEND_ID, sendId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val sentIntents = ArrayList<PendingIntent?>(parts.size).apply {
            repeat(parts.size - 1) { add(null) }
            add(sentPi)
        }
        sms.sendMultipartTextMessage(number, null, parts, sentIntents, null)
    }
}

class SmsResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != SmsSender.ACTION_SENT) return
        val id = intent.getLongExtra(SendScheduler.EXTRA_SEND_ID, -1)
        val ok = resultCode == Activity.RESULT_OK
        val note = if (ok) L.t("SMS sent", "ה-SMS נשלח") else L.t("SMS failed (radio result code $resultCode)", "שליחת ה-SMS נכשלה (קוד $resultCode)")
        val pending = goAsync()
        val app = context.applicationContext as AutoAgentApp
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val s = app.db.sends().get(id)
                app.db.sends().setStatus(id, if (ok) SendStatus.SENT else SendStatus.FAILED, note)
                app.log("sms", "$note to ${s?.recipientLabel ?: "?"}", ok)
                Notifier.event(context, if (ok) L.t("Scheduled SMS sent", "SMS מתוזמן נשלח") else L.t("Scheduled SMS failed", "SMS מתוזמן נכשל"), "${s?.recipientLabel ?: ""}: $note", "schedules")
                AgentStatus.refresh(context)
            } finally {
                pending.finish()
            }
        }
    }
}
