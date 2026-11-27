package com.autoagent.app.access

import com.autoagent.app.util.L
import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.autoagent.app.R
import com.autoagent.app.data.ScheduledSend
import com.autoagent.app.util.Notifier
import kotlinx.coroutines.delay

/**
 * Drives a messaging app the user picked, only for a scheduled message the user wrote.
 * It opens the app with the saved text, verifies the screen, taps Send once, and verifies again.
 * If anything on screen does not match what is expected it aborts instead of guessing.
 */
class AutomationService : AccessibilityService() {

    data class Outcome(
        val sent: Boolean,
        val aborted: Boolean,
        val note: String,
        val waitUnlock: Boolean = false,
        /** "Prepare only": the chat is open with the text in the box; you press Send. */
        val prepared: Boolean = false,
    )

    private val unlockReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: Intent) {
            if (intent.action == Intent.ACTION_USER_PRESENT) com.autoagent.app.schedule.SendScheduler.resumeWaitingUnlock(context)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        runCatching {
            androidx.core.content.ContextCompat.registerReceiver(
                this, unlockReceiver, android.content.IntentFilter(Intent.ACTION_USER_PRESENT), androidx.core.content.ContextCompat.RECEIVER_EXPORTED,
            )
        }
        AccessibilityHealth.onConnected(this)
        // Recovery: anything that was waiting for the service continues now.
        com.autoagent.app.schedule.SendScheduler.resumeWaitingService(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Only a heartbeat. The window tree is read on demand inside performSend; other apps are not observed.
        AccessibilityHealth.onEvent()
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        AccessibilityHealth.onDisconnected(this)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        runCatching { unregisterReceiver(unlockReceiver) }
        AccessibilityHealth.onDisconnected(this)
        super.onDestroy()
    }

    private fun abort(note: String) = Outcome(sent = false, aborted = true, note = note)
    private fun fail(note: String) = Outcome(sent = false, aborted = false, note = note)
    private fun ok(note: String) = Outcome(sent = true, aborted = false, note = note)
    private fun prepared(note: String) = Outcome(sent = false, aborted = false, note = note, prepared = true)

    private fun isPhone(s: String) = MessengerProfiles.isPhone(s)

    /** True when the app opens straight into the right chat (deep link), so no recipient has to be picked on screen. */
    private fun hasDirectLink(job: ScheduledSend) = MessengerProfiles.forPackage(job.packageName).deepLink?.invoke(job.recipient, job.text) != null

    private fun buildIntent(job: ScheduledSend): Intent? {
        val pm = packageManager
        MessengerProfiles.forPackage(job.packageName).deepLink?.invoke(job.recipient, job.text)?.let { link ->
            val i = Intent(Intent.ACTION_VIEW, Uri.parse(link)).setPackage(job.packageName)
            if (i.resolveActivity(pm) != null) return i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val share = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, job.text).setPackage(job.packageName)
        return if (share.resolveActivity(pm) != null) share.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP) else null
    }

    private fun progress(job: ScheduledSend, show: Boolean) {
        val nm = NotificationManagerCompat.from(this)
        if (!show) { nm.cancel(Notifier.ID_SEND_WORK + 100); return }
        if (!Notifier.canPost(this)) return
        val n = NotificationCompat.Builder(this, Notifier.CH_STATUS)
            .setSmallIcon(R.drawable.ic_stat_agent)
            .setContentTitle(L.t("AutoAgent is operating ${job.appLabel}", "AutoAgent מפעיל את ${job.appLabel}"))
            .setContentText(L.t("Sending your scheduled message to ${job.recipientLabel}", "שולח את ההודעה המתוזמנת ל${job.recipientLabel}"))
            .setOngoing(true)
            .build()
        try { nm.notify(Notifier.ID_SEND_WORK + 100, n) } catch (_: SecurityException) {}
    }

    suspend fun performSend(job: ScheduledSend): Outcome {
        val km = getSystemService(KeyguardManager::class.java)
        if (km.isKeyguardLocked) {
            // Swipe-only lock: dismissed automatically. PIN/biometric: Android shows its unlock prompt; we wait for you.
            val unlocked = kotlinx.coroutines.withTimeoutOrNull(if (km.isDeviceSecure) 45_000L else 8_000L) {
                UnlockActivity.request(this@AutomationService, job.recipientLabel).await()
            } == true
            if (!unlocked || km.isKeyguardLocked) {
                return Outcome(false, false, L.t("Phone is locked; waiting for you to unlock (up to 60 min).", "הטלפון נעול; ממתין שתפתח את הנעילה (עד 60 דקות)."), waitUnlock = true)
            }
            kotlinx.coroutines.delay(600)
        }
        if (packageManager.getLaunchIntentForPackage(job.packageName) == null) return abort(L.t("${job.appLabel} is not installed", "${job.appLabel} לא מותקנת"))
        val intent = buildIntent(job) ?: return abort(L.t("${job.appLabel} does not accept shared text", "${job.appLabel} לא מקבלת טקסט משותף"))
        progress(job, true)
        try {
            return drive(job, intent)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // A stale node or an app crash must never take the service down.
            AccessibilityHealth.onError("drive", e)
            return abort(L.t("Unexpected screen error: ${e.javaClass.simpleName}", "שגיאת מסך לא צפויה: ${e.javaClass.simpleName}"))
        } finally {
            progress(job, false)
        }
    }

    private suspend fun drive(job: ScheduledSend, intent: Intent): Outcome {
        try {
            startActivity(intent)
        } catch (e: Exception) {
            return abort(L.t("Could not open ${job.appLabel}: ${e.message}", "לא הצלחתי לפתוח את ${job.appLabel}: ${e.message}"))
        }
        val saved = job.text.trim()
        if (!waitFor(10_000) { rootInActiveWindow?.packageName?.toString() == job.packageName }) {
            return abort(L.t("${job.appLabel} did not come to the foreground", "${job.appLabel} לא נפתחה על המסך"))
        }
        val directChat = hasDirectLink(job)
        var recipientChosen = directChat
        var shareConfirmed = false
        var textFilledByUs = false
        var wrongScreenCount = 0
        val deadline = System.currentTimeMillis() + 30_000

        while (System.currentTimeMillis() < deadline) {
            delay(700)
            val root = rootInActiveWindow ?: continue
            if (root.packageName?.toString() != job.packageName) {
                if (++wrongScreenCount >= 4) return abort(L.t("Screen switched to ${root.packageName}; stopped", "המסך עבר ל-${root.packageName}; עצרתי"))
                continue
            }
            wrongScreenCount = 0

            val inputs = collect(root) { it.isEditable && it.isVisibleToUser }
            val send = findSendButton(root, job.packageName)

            if (shareConfirmed && hasBubble(root, saved)) {
                performGlobalAction(GLOBAL_ACTION_HOME)
                return ok(L.t("Sent via ${job.appLabel}; message visible in chat", "נשלח ב-${job.appLabel}; ההודעה מופיעה בצ'אט"))
            }

            if (send != null && inputs.size == 1) {
                val input = inputs[0]
                val current = if (input.isShowingHintText) "" else input.text?.toString()?.trim().orEmpty()
                when {
                    current == saved && !job.autoSend -> {
                        return prepared(L.t("Message is ready in ${job.appLabel}; press Send yourself", "ההודעה מוכנה ב-${job.appLabel}; לחץ שלח בעצמך"))
                    }
                    current == saved -> {
                        if (!clickNode(send)) return fail(L.t("Send button did not accept the tap", "כפתור השליחה לא הגיב ללחיצה"))
                        delay(1500)
                        val after = rootInActiveWindow
                        val stillThere = after != null && collect(after) { it.isEditable && it.isVisibleToUser }
                            .any { !it.isShowingHintText && it.text?.toString()?.trim() == saved }
                        if (stillThere) return fail(L.t("Tapped Send but the text is still in the box; not retrying", "לחצתי שלח אבל הטקסט עדיין בתיבה; לא מנסה שוב"))
                        performGlobalAction(GLOBAL_ACTION_HOME)
                        return ok(L.t("Sent via ${job.appLabel}", "נשלח ב-${job.appLabel}"))
                    }
                    current.isEmpty() && recipientChosen && !textFilledByUs && !shareConfirmed -> {
                        val args = Bundle().apply {
                            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, job.text)
                        }
                        if (!input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                            return abort(L.t("Could not paste the saved text into ${job.appLabel}", "לא הצלחתי להדביק את הטקסט השמור ב-${job.appLabel}"))
                        }
                        textFilledByUs = true
                        continue
                    }
                    current.isEmpty() -> continue
                    else -> return abort(L.t("Text box shows different text than your saved message; not sent", "בתיבת הטקסט יש טקסט שונה מההודעה ששמרת; לא נשלח"))
                }
            }

            if (send != null && inputs.isEmpty() && recipientChosen && !directChat && !shareConfirmed && !job.autoSend) {
                return prepared(L.t("Recipient selected in ${job.appLabel}; press Send yourself", "הנמען נבחר ב-${job.appLabel}; לחץ שלח בעצמך"))
            }

            if (send != null && inputs.isEmpty() && recipientChosen && !directChat && !shareConfirmed) {
                if (!clickNode(send)) return fail(L.t("Send button did not accept the tap", "כפתור השליחה לא הגיב ללחיצה"))
                shareConfirmed = true
                continue
            }

            if (!recipientChosen) {
                val label = job.recipientLabel.trim()
                val matches = collect(root) {
                    !it.isEditable && it.isVisibleToUser &&
                        (it.text?.toString()?.trim()?.equals(label, ignoreCase = true) == true)
                }
                when {
                    matches.size > 1 -> return abort(L.t("More than one \"$label\" on screen; not guessing", "יש יותר מ-\"$label\" אחד על המסך; לא מנחש"))
                    matches.size == 1 -> {
                        if (!clickNode(matches[0])) return abort(L.t("Could not select \"$label\"", "לא הצלחתי לבחור את \"$label\""))
                        recipientChosen = true
                    }
                }
            }
        }
        return abort(L.t("Screen never matched the expected chat with your saved text (30s)", "המסך לא התאים לצ'אט הצפוי עם הטקסט השמור (30 שניות)"))
    }

    private suspend fun waitFor(timeoutMs: Long, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (cond()) return true
            delay(300)
        }
        return false
    }

    private fun collect(root: AccessibilityNodeInfo, pred: (AccessibilityNodeInfo) -> Boolean): List<AccessibilityNodeInfo> {
        val out = mutableListOf<AccessibilityNodeInfo>()
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var visited = 0
        while (stack.isNotEmpty() && visited < 3000) {
            val n = stack.removeLast()
            visited++
            if (pred(n)) out += n
            for (i in 0 until n.childCount) n.getChild(i)?.let { stack.addLast(it) }
        }
        return out
    }

    private fun hasBubble(root: AccessibilityNodeInfo, saved: String): Boolean =
        collect(root) { !it.isEditable && it.isVisibleToUser && it.text?.toString()?.trim() == saved }.isNotEmpty()

    private fun findSendButton(root: AccessibilityNodeInfo, pkg: String): AccessibilityNodeInfo? {
        for (id in MessengerProfiles.sendViewIds(pkg)) {
            root.findAccessibilityNodeInfosByViewId(id)
                .firstOrNull { it.isVisibleToUser && it.isEnabled }
                ?.let { return it }
        }
        return collect(root) { n ->
            n.isVisibleToUser && n.isEnabled && !n.isEditable &&
                (MessengerProfiles.isSendLabel(n.contentDescription) || MessengerProfiles.isSendLabel(n.text))
        }.firstOrNull()
    }

    private fun clickNode(node: AccessibilityNodeInfo): Boolean {
        var n: AccessibilityNodeInfo? = node
        var depth = 0
        while (n != null && depth < 6) {
            if (n.isClickable) return n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            n = n.parent
            depth++
        }
        return false
    }

    companion object {
        @Volatile
        var instance: AutomationService? = null
            private set

        fun isEnabled(context: Context): Boolean = AccessibilityHealth.isEnabledInSettings(context)
    }
}
