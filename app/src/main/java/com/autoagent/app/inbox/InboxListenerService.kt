package com.autoagent.app.inbox

import android.app.Notification
import android.content.pm.PackageManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import com.autoagent.app.AutoAgentApp
import com.autoagent.app.data.InboxEntry
import kotlinx.coroutines.launch

/** Stores incoming notification text (SMS, WhatsApp, any app) locally in Room. Nothing leaves the device. */
class InboxListenerService : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        val n = sbn.notification ?: return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        if (n.flags and Notification.FLAG_ONGOING_EVENT != 0) return
        val category = n.category
        if (category == Notification.CATEGORY_PROGRESS || category == Notification.CATEGORY_TRANSPORT ||
            category == Notification.CATEGORY_SERVICE || category == Notification.CATEGORY_SYSTEM
        ) return

        val app = applicationContext as AutoAgentApp
        val label = appLabel(sbn.packageName)
        val entries = mutableListOf<InboxEntry>()

        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)
        if (style != null && style.messages.isNotEmpty()) {
            val conv = style.conversationTitle?.toString()
            for (m in style.messages) {
                val text = m.text?.toString()?.trim().orEmpty()
                if (text.isEmpty()) continue
                val who = m.person?.name?.toString() ?: n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: label
                val sender = if (conv != null && conv != who) "$who @ $conv" else who
                val ts = if (m.timestamp > 0) m.timestamp else sbn.postTime
                entries += entry(sbn.packageName, label, sender, text, ts)
            }
        } else {
            val title = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
            val text = (n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: n.extras.getCharSequence(Notification.EXTRA_TEXT))
                ?.toString()?.trim().orEmpty()
            if (text.isEmpty() && title.isEmpty()) return
            entries += entry(sbn.packageName, label, title.ifEmpty { label }, text.ifEmpty { title }, sbn.postTime)
        }
        app.appScope.launch { entries.forEach { app.db.inbox().insert(it) } }
    }

    private fun entry(pkg: String, label: String, sender: String, text: String, ts: Long) = InboxEntry(
        packageName = pkg,
        appLabel = label,
        sender = sender,
        text = text.take(4000),
        postedAt = ts,
        dedupKey = "$pkg|$sender|${text.hashCode()}|${ts / 60_000}",
    )

    private fun appLabel(pkg: String): String = try {
        val info = packageManager.getApplicationInfo(pkg, 0)
        packageManager.getApplicationLabel(info).toString()
    } catch (_: PackageManager.NameNotFoundException) {
        pkg
    }
}
