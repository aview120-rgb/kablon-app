package com.autoagent.app.access

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.autoagent.app.R
import com.autoagent.app.data.ScheduledSend
import com.autoagent.app.data.SendChannel
import com.autoagent.app.util.Notifier
import kotlinx.coroutines.delay

/**
 * Drives a messaging app the user picked, only for a scheduled message the user wrote.
 * It opens the app with the saved text, verifies the screen, taps Send once, and verifies again.
 * If anything on screen does not match what is expected it aborts instead of guessing.
 */
class AutomationService : AccessibilityService() {

    data class Outcome(val sent: Boolean, val aborted: Boolean, val note: String)

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // The window tree is polled on demand inside performSend; no background observation of other apps.
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    private fun abort(note: String) = Outcome(sent = false, aborted = true, note = note)
    private fun fail(note: String) = Outcome(sent = false, aborted = false, note = note)
    private fun ok(note: String) = Outcome(sent = true, aborted = false, note = note)

    private fun isPhone(s: String) = s.trim().matches(Regex("""\+?[\d\s\-()]{6,}"""))

    private fun buildIntent(job: ScheduledSend): Intent? {
        val pm = packageManager
        if (job.channel == SendChannel.WHATSAPP && isPhone(job.recipient)) {
            val digits = job.recipient.filter { it.isDigit() }
            val uri = Uri.parse("https://api.whatsapp.com/send?phone=$digits&text=" + Uri.encode(job.text))
            val i = Intent(Intent.ACTION_VIEW, uri).setPackage(job.packageName)
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
            .setContentTitle("AutoAgent is operating ${job.appLabel}")
            .setContentText("Sending your scheduled message to ${job.recipientLabel}")
            .setOngoing(true)
            .build()
        try { nm.notify(Notifier.ID_SEND_WORK + 100, n) } catch (_: SecurityException) {}
    }

    suspend fun performSend(job: ScheduledSend): Outcome {
        val km = getSystemService(KeyguardManager::class.java)
        if (km.isKeyguardLocked) return abort("Phone was locked, so the screen could not be verified. Not sent.")
        if (packageManager.getLaunchIntentForPackage(job.packageName) == null) return abort("${job.appLabel} is not installed")
        val intent = buildIntent(job) ?: return abort("${job.appLabel} does not accept shared text")
        progress(job, true)
        try {
            return drive(job, intent)
        } finally {
            progress(job, false)
        }
    }

    private suspend fun drive(job: ScheduledSend, intent: Intent): Outcome {
        try {
            startActivity(intent)
        } catch (e: Exception) {
            return abort("Could not open ${job.appLabel}: ${e.message}")
        }
        val saved = job.text.trim()
        if (!waitFor(10_000) { rootInActiveWindow?.packageName?.toString() == job.packageName }) {
            return abort("${job.appLabel} did not come to the foreground")
        }
        val directChat = job.channel == SendChannel.WHATSAPP && isPhone(job.recipient)
        var recipientChosen = directChat
        var shareConfirmed = false
        var textFilledByUs = false
        var wrongScreenCount = 0
        val deadline = System.currentTimeMillis() + 30_000

        while (System.currentTimeMillis() < deadline) {
            delay(700)
            val root = rootInActiveWindow ?: continue
            if (root.packageName?.toString() != job.packageName) {
                if (++wrongScreenCount >= 4) return abort("Screen switched to ${root.packageName}; stopped")
                continue
            }
            wrongScreenCount = 0

            val inputs = collect(root) { it.isEditable && it.isVisibleToUser }
            val send = findSendButton(root, job.packageName)

            if (shareConfirmed && hasBubble(root, saved)) {
                performGlobalAction(GLOBAL_ACTION_HOME)
                return ok("Sent via ${job.appLabel}; message visible in chat")
            }

            if (send != null && inputs.size == 1) {
                val input = inputs[0]
                val current = if (input.isShowingHintText) "" else input.text?.toString()?.trim().orEmpty()
                when {
                    current == saved -> {
                        if (!clickNode(send)) return fail("Send button did not accept the tap")
                        delay(1500)
                        val after = rootInActiveWindow
                        val stillThere = after != null && collect(after) { it.isEditable && it.isVisibleToUser }
                            .any { !it.isShowingHintText && it.text?.toString()?.trim() == saved }
                        if (stillThere) return fail("Tapped Send but the text is still in the box; not retrying")
                        performGlobalAction(GLOBAL_ACTION_HOME)
                        return ok("Sent via ${job.appLabel}")
                    }
                    current.isEmpty() && recipientChosen && !textFilledByUs && !shareConfirmed -> {
                        val args = Bundle().apply {
                            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, job.text)
                        }
                        if (!input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                            return abort("Could not paste the saved text into ${job.appLabel}")
                        }
                        textFilledByUs = true
                        continue
                    }
                    current.isEmpty() -> continue
                    else -> return abort("Text box shows different text than your saved message; not sent")
                }
            }

            if (send != null && inputs.isEmpty() && recipientChosen && !directChat && !shareConfirmed) {
                if (!clickNode(send)) return fail("Send button did not accept the tap")
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
                    matches.size > 1 -> return abort("More than one \"$label\" on screen; not guessing")
                    matches.size == 1 -> {
                        if (!clickNode(matches[0])) return abort("Could not select \"$label\"")
                        recipientChosen = true
                    }
                }
            }
        }
        return abort("Screen never matched the expected chat with your saved text (30s)")
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
        for (id in listOf("send", "send_button", "sendButton", "btn_send", "chat_send_button", "conversation_send")) {
            root.findAccessibilityNodeInfosByViewId("$pkg:id/$id")
                .firstOrNull { it.isVisibleToUser && it.isEnabled }
                ?.let { return it }
        }
        return collect(root) { n ->
            n.isVisibleToUser && n.isEnabled && !n.isEditable &&
                (SEND_WORDS.contains(n.contentDescription?.toString()?.trim()?.lowercase()) ||
                    SEND_WORDS.contains(n.text?.toString()?.trim()?.lowercase()))
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

        private val SEND_WORDS = setOf("send", "שלח", "שליחה", "enviar", "envoyer", "senden", "invia", "отправить", "إرسال")

        fun isEnabled(context: Context): Boolean {
            val flat = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            val me = ComponentName(context, AutomationService::class.java)
            return flat.split(':').any { ComponentName.unflattenFromString(it) == me }
        }
    }
}
