package com.autoagent.app.calls

import com.autoagent.app.util.L
import android.app.PendingIntent
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.telecom.VideoProfile
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.autoagent.app.AutoAgentApp
import com.autoagent.app.R
import com.autoagent.app.util.Contacts
import com.autoagent.app.util.Notifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Our in-call UI service; Telecom binds it while AutoAgent holds the default-dialer role. */
class AgentInCallService : InCallService() {
    private val handler = Handler(Looper.getMainLooper())
    private val pendingAssistant = HashSet<String>()
    private val sessions = HashMap<String, AssistantSession>()

    private val callback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            CallManager.publish()
            updateNotification()
            val id = CallManager.idOf(call)
            if (state == Call.STATE_ACTIVE && pendingAssistant.remove(id)) startAssistant(call)
            if (state == Call.STATE_ACTIVE && !CallManager.isIncoming(call) && HoldWatcher.armedForNextCall) {
                HoldWatcher.armedForNextCall = false
                HoldWatcher.start(this@AgentInCallService) { CallManager.stateOf(call) == Call.STATE_ACTIVE || CallManager.stateOf(call) == Call.STATE_HOLDING }
            }
        }
        override fun onDetailsChanged(call: Call, details: Call.Details) { CallManager.publish() }
    }

    override fun onCreate() {
        super.onCreate()
        CallManager.service = this
    }

    override fun onDestroy() {
        CallManager.service = null
        super.onDestroy()
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        val incoming = CallManager.isIncoming(call)
        CallManager.add(call, incoming)
        call.registerCallback(callback)
        val app = applicationContext as AutoAgentApp
        app.appScope.launch {
            val name = withContext(Dispatchers.IO) { Contacts.nameForNumber(app, CallManager.numberOf(call)) }
            CallManager.setName(call, name)
            withContext(Dispatchers.Main) {
                updateNotification()
                if (incoming && CallManager.stateOf(call) == Call.STATE_RINGING) considerAutoAnswer(call, name)
            }
        }
        if (!incoming) openUi()
        updateNotification()
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        call.unregisterCallback(callback)
        val id = CallManager.idOf(call)
        pendingAssistant.remove(id)
        sessions.remove(id)?.stop()
        if (CallManager.count() <= 1) HoldWatcher.stop()
        CallManager.remove(call)
        updateNotification()
    }

    @Deprecated("Deprecated in Java")
    override fun onCallAudioStateChanged(audioState: CallAudioState?) {
        CallManager.audioState.value = audioState
    }

    private fun considerAutoAnswer(call: Call, contactName: String?) {
        val app = applicationContext as AutoAgentApp
        val s = app.settings.current
        if (!s.autoAnswerEnabled) return
        val number = CallManager.numberOf(call)
        when {
            number.isBlank() -> { app.log("autoanswer", L.t("Hidden number: not auto-answered", "מספר חסוי: לא נענה אוטומטית"), false); return }
            !Contacts.hasPermission(app) -> {
                app.log("autoanswer", L.t("Contacts permission off: cannot verify $number is unknown, not auto-answered", "הרשאת אנשי קשר כבויה: אי אפשר לוודא ש-$number לא מוכר, לא נענה"), false); return
            }
            contactName != null -> return
            CallManager.count() > 1 -> { app.log("autoanswer", L.t("Another call active: $number not auto-answered", "יש שיחה פעילה אחרת: $number לא נענה"), false); return }
        }
        app.log("autoanswer", L.t("Unknown caller $number; answering in ${s.autoAnswerDelaySec}s unless you pick up", "מתקשר לא מוכר $number; עונה בעוד ${s.autoAnswerDelaySec} שניות אם לא תענה"), true)
        handler.postDelayed({
            if (CallManager.stateOf(call) == Call.STATE_RINGING && app.settings.current.autoAnswerEnabled) {
                pendingAssistant += CallManager.idOf(call)
                CallManager.markAssisted(call)
                call.answer(VideoProfile.STATE_AUDIO_ONLY)
                openUi()
            }
        }, s.autoAnswerDelaySec * 1000L)
    }

    private fun startAssistant(call: Call) {
        val control = object : CallControl {
            override val number: String = CallManager.numberOf(call)
            override fun setSpeaker(on: Boolean) = CallManager.setSpeaker(on)
            override fun hangUp() { if (CallManager.stateOf(call) != Call.STATE_DISCONNECTED) call.disconnect() }
            override fun isActive(): Boolean = CallManager.stateOf(call) == Call.STATE_ACTIVE
        }
        val session = AssistantSession(this, control, isTest = false)
        sessions[CallManager.idOf(call)] = session
        session.start((applicationContext as AutoAgentApp).appScope)
    }

    private fun openUi() {
        runCatching {
            startActivity(Intent(this, InCallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
    }

    private fun updateNotification() {
        val nm = NotificationManagerCompat.from(this)
        val calls = CallManager.calls.value
        if (calls.isEmpty() || !Notifier.canPost(this)) { nm.cancel(Notifier.ID_INCOMING_CALL); return }
        val c = calls.first()
        val ui = PendingIntent.getActivity(
            this, 10, Intent(this, InCallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val who = c.name ?: c.number.ifBlank { L.t("Unknown", "לא ידוע") }
        val b = NotificationCompat.Builder(this, Notifier.CH_CALL)
            .setSmallIcon(R.drawable.ic_stat_agent)
            .setContentTitle(if (c.assistant) L.t("Assistant on call: $who", "העוזר בשיחה עם: $who") else "${CallManager.stateLabel(c.state)}: $who")
            .setContentText(if (c.assistant) L.t("Answered by your assistant (disclosed and recorded)", "נענה על ידי העוזר (בהודעה גלויה ובהקלטה)") else L.t("Tap to open", "הקש לפתיחה"))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setContentIntent(ui)
        if (c.state == Call.STATE_RINGING) {
            b.setFullScreenIntent(ui, true)
            b.addAction(0, L.t("Answer", "מענה"), CallActionReceiver.pending(this, CallActionReceiver.ACTION_ANSWER, c.id))
            b.addAction(0, L.t("Reject", "דחייה"), CallActionReceiver.pending(this, CallActionReceiver.ACTION_REJECT, c.id))
        } else {
            b.addAction(0, L.t("Hang up", "ניתוק"), CallActionReceiver.pending(this, CallActionReceiver.ACTION_HANGUP, c.id))
        }
        try { nm.notify(Notifier.ID_INCOMING_CALL, b.build()) } catch (_: SecurityException) {}
    }
}
