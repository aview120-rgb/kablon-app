package com.autoagent.app.ui

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.autoagent.app.AutoAgentApp
import com.autoagent.app.alarm.AlarmScheduler
import com.autoagent.app.calls.PhoneAccounts
import com.autoagent.app.data.AlarmEntity
import com.autoagent.app.data.CallRecord
import com.autoagent.app.data.KeywordRule
import com.autoagent.app.data.Receipt
import com.autoagent.app.data.ReceiptSource
import com.autoagent.app.data.ScheduledSend
import com.autoagent.app.data.SecretBox
import com.autoagent.app.data.SendStatus
import com.autoagent.app.inbox.Summarizer
import com.autoagent.app.receipts.ImapWorker
import com.autoagent.app.receipts.ReceiptIngest
import com.autoagent.app.schedule.SendScheduler
import com.autoagent.app.service.AgentStatus
import com.autoagent.app.util.L
import com.autoagent.app.util.TimeText
import com.autoagent.app.voice.Speaker
import com.autoagent.app.voice.SpeechListener
import com.autoagent.app.voice.VoiceCommands
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private val AndroidViewModel.app get() = getApplication<AutoAgentApp>()

/* ---------------- Home ---------------- */

data class NextAction(val at: Long?, val title: String, val detail: String, val route: String, val callNumber: String? = null)

class HomeViewModel(application: Application) : AndroidViewModel(application) {
    val dialText = MutableStateFlow("")
    val message = MutableStateFlow<String?>(null)

    val actions: StateFlow<List<NextAction>> = combine(
        app.db.alarms().observeAll(),
        app.db.sends().observeAll(),
        app.db.calls().observeAll(),
        app.settings.state,
        L.hebrewFlow,
    ) { alarms, sends, calls, settings, _ ->
        val now = System.currentTimeMillis()
        val out = mutableListOf<NextAction>()
        calls.filter { !it.callbackDone && !it.isTest }.take(5).forEach {
            val last = it.transcript.lines().lastOrNull { l -> l.startsWith("Caller:") || l.startsWith("מתקשר:") }?.substringAfter(':')?.trim()
            out += NextAction(it.startedAt, L.t("Call back ${it.number}", "לחזור ל-${it.number}"), last ?: L.t("Assistant took this call", "העוזר ענה לשיחה הזו"), "autoanswer", it.number)
        }
        sends.filter { it.status == SendStatus.PENDING }.sortedBy { it.sendAt }.take(5).forEach {
            out += NextAction(it.sendAt, "${it.appLabel} → ${it.recipientLabel}", it.text.take(80), "schedules")
        }
        sends.filter { it.status == SendStatus.ABORTED || it.status == SendStatus.FAILED }.filter { now - it.sendAt < 86_400_000L }.take(3).forEach {
            out += NextAction(it.sendAt, L.t("Not sent: ${it.recipientLabel}", "לא נשלח: ${it.recipientLabel}"), it.resultNote, "schedules")
        }
        alarms.filter { it.enabled && it.triggerAt > now }.take(3).forEach {
            out += NextAction(it.triggerAt, L.t("Alarm: ${it.label}", "שעון מעורר: ${it.label}"), TimeText.format(it.triggerAt), "schedules")
        }
        out += NextAction(
            null,
            if (settings.autoAnswerEnabled) L.t("Auto-answer is ON", "מענה אוטומטי פועל") else L.t("Auto-answer is OFF", "מענה אוטומטי כבוי"),
            if (settings.autoAnswerEnabled) L.t("Unknown numbers get the assistant after ${settings.autoAnswerDelaySec}s", "מספרים לא מוכרים יקבלו את העוזר אחרי ${settings.autoAnswerDelaySec} שניות")
            else L.t("Tap to configure", "הקש להגדרה"),
            "autoanswer",
        )
        out
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val inboxCount: StateFlow<Int> = app.db.inbox().observeRecent()
        .map { list -> list.count { System.currentTimeMillis() - it.postedAt < 86_400_000L } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    fun call(number: String) {
        message.value = PhoneAccounts.placeCall(app, number)
    }

    fun refreshService() = viewModelScope.launch { AgentStatus.refresh(app) }

    val language = app.settings.state.map { it.language }.stateIn(viewModelScope, SharingStarted.Eagerly, app.settings.current.language)

    /** Cycles auto → Hebrew → English → auto. */
    fun cycleLanguage() {
        val next = when (app.settings.current.language) { L.AUTO -> L.HE; L.HE -> L.EN; else -> L.AUTO }
        app.settings.setLanguage(next)
        com.autoagent.app.util.Notifier.createChannels(app)
        viewModelScope.launch { AgentStatus.refresh(app) }
    }
}

/* ---------------- Schedules ---------------- */

data class TargetApp(val packageName: String, val label: String)

class SchedulesViewModel(application: Application) : AndroidViewModel(application) {
    val sends = app.db.sends().observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val alarms = app.db.alarms().observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val message = MutableStateFlow<String?>(null)

    /** Installed apps that accept shared text, WhatsApp first. */
    fun shareTargets(): List<TargetApp> {
        val pm = app.packageManager
        val intent = Intent(Intent.ACTION_SEND).setType("text/plain")
        val infos = if (Build.VERSION.SDK_INT >= 33) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, 0)
        }
        return infos.map { TargetApp(it.activityInfo.packageName, pm.getApplicationLabel(it.activityInfo.applicationInfo).toString()) }
            .filter { it.packageName != app.packageName }
            .distinctBy { it.packageName }
            .sortedWith(compareByDescending<TargetApp> { it.packageName.startsWith("com.whatsapp") }.thenBy { it.label.lowercase() })
    }

    fun schedule(send: ScheduledSend, onDone: () -> Unit) = viewModelScope.launch {
        when (val r = SendScheduler.create(app, send)) {
            is SendScheduler.CreateResult.Ok -> {
                message.value = if (r.exact) L.t("Scheduled for ${TimeText.format(send.sendAt)}", "תוזמן ל-${TimeText.format(send.sendAt)}") else
                    L.t("Scheduled, but exact alarms are off so it may be a few minutes late. Enable it in Permissions.", "תוזמן, אבל שעונים מדויקים כבויים ולכן זה עלול להתאחר בכמה דקות. אפשר להפעיל במסך ההרשאות.")
                onDone()
            }
            is SendScheduler.CreateResult.Rejected -> message.value = r.reason
        }
    }

    fun cancel(send: ScheduledSend) = viewModelScope.launch { SendScheduler.cancel(app, send) }

    fun delete(send: ScheduledSend) = viewModelScope.launch(Dispatchers.IO) {
        if (send.status == SendStatus.PENDING) SendScheduler.cancel(app, send)
        app.db.sends().delete(send)
    }

    fun addAlarm(at: Long, label: String) = viewModelScope.launch {
        val a = AlarmEntity(triggerAt = at, label = label.ifBlank { L.t("Alarm", "שעון מעורר") })
        val id = withContext(Dispatchers.IO) { app.db.alarms().insert(a) }
        message.value = if (AlarmScheduler.schedule(app, a.copy(id = id))) L.t("Alarm set for ${TimeText.format(at)}", "שעון מעורר נקבע ל-${TimeText.format(at)}")
        else L.t("Alarm saved but exact alarms are not allowed. Enable \"Alarms & reminders\" in Permissions.", "השעון נשמר, אבל אין הרשאה לשעונים מדויקים. צריך להפעיל \"שעונים ותזכורות\" במסך ההרשאות.")
        AgentStatus.refresh(app)
    }

    fun toggleAlarm(a: AlarmEntity, on: Boolean) = viewModelScope.launch {
        if (on && a.triggerAt <= System.currentTimeMillis()) {
            message.value = L.t("That time has passed; add a new alarm", "השעה הזו כבר עברה; צריך להוסיף שעון חדש")
            return@launch
        }
        withContext(Dispatchers.IO) { app.db.alarms().update(a.copy(enabled = on)) }
        if (on) AlarmScheduler.schedule(app, a) else AlarmScheduler.cancel(app, a.id)
        AgentStatus.refresh(app)
    }

    fun deleteAlarm(a: AlarmEntity) = viewModelScope.launch {
        AlarmScheduler.cancel(app, a.id)
        withContext(Dispatchers.IO) { app.db.alarms().delete(a) }
        AgentStatus.refresh(app)
    }
}

/* ---------------- Auto-answer ---------------- */

class AutoAnswerViewModel(application: Application) : AndroidViewModel(application) {
    val settings = app.settings.state
    val rules = app.db.rules().observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val records = app.db.calls().observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val message = MutableStateFlow<String?>(null)
    private var player: MediaPlayer? = null
    private val _playing = MutableStateFlow<Long?>(null)
    val playing: StateFlow<Long?> = _playing.asStateFlow()

    fun setEnabled(on: Boolean) {
        app.settings.setAutoAnswer(on)
        app.log("autoanswer", "Auto-answer turned ${if (on) "on" else "off"}", true)
        viewModelScope.launch { AgentStatus.refresh(app) }
        if (on && !PhoneAccounts.isDefaultDialer(app)) message.value = L.t("Auto-answer needs AutoAgent as the default phone app (see Permissions).", "מענה אוטומטי דורש ש-AutoAgent תהיה אפליקציית הטלפון הראשית (במסך ההרשאות).")
    }

    fun setDelay(sec: Int) = app.settings.setAutoAnswerDelay(sec)
    fun setTurns(n: Int) = app.settings.setMaxTurns(n)

    fun saveRule(rule: KeywordRule) = viewModelScope.launch(Dispatchers.IO) {
        if (rule.id == 0L) app.db.rules().insert(rule) else app.db.rules().update(rule)
    }

    fun deleteRule(rule: KeywordRule) = viewModelScope.launch(Dispatchers.IO) { app.db.rules().delete(rule) }

    fun markDone(r: CallRecord) = viewModelScope.launch(Dispatchers.IO) { app.db.calls().update(r.copy(callbackDone = true)) }

    fun deleteRecord(r: CallRecord) = viewModelScope.launch(Dispatchers.IO) {
        r.audioPath?.let { File(it).delete() }
        app.db.calls().delete(r)
    }

    fun callBack(r: CallRecord) {
        message.value = PhoneAccounts.placeCall(app, r.number)
    }

    fun testCall() {
        message.value = PhoneAccounts.startTestCall(app) ?: L.t("Test call started: the assistant speaks on your speaker; play the caller.", "שיחת הבדיקה התחילה: העוזר מדבר ברמקול, ואתה משחק את המתקשר.")
    }

    fun togglePlay(r: CallRecord) {
        if (_playing.value == r.id) { stopPlayback(); return }
        stopPlayback()
        val path = r.audioPath ?: return
        player = runCatching {
            MediaPlayer().apply {
                setDataSource(path)
                setOnCompletionListener { stopPlayback() }
                prepare()
                start()
            }
        }.getOrNull()
        _playing.value = if (player != null) r.id else null
    }

    private fun stopPlayback() {
        player?.runCatching { stop(); release() }
        player = null
        _playing.value = null
    }

    override fun onCleared() {
        stopPlayback()
        super.onCleared()
    }
}

/* ---------------- Inbox ---------------- */

class InboxViewModel(application: Application) : AndroidViewModel(application) {
    val windowHours = MutableStateFlow(24)
    val entries = app.db.inbox().observeRecent().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val _summary = MutableStateFlow<Summarizer.Summary?>(null)
    val summary: StateFlow<Summarizer.Summary?> = _summary.asStateFlow()
    private var speaker: Speaker? = null

    fun summarize() = viewModelScope.launch {
        val since = System.currentTimeMillis() - windowHours.value * 3_600_000L
        val list = withContext(Dispatchers.IO) { app.db.inbox().since(since) }
        _summary.value = Summarizer.summarize(list)
    }

    fun readAloud() = viewModelScope.launch {
        val s = _summary.value ?: return@launch
        val sp = speaker ?: Speaker(app).also { speaker = it }
        sp.speak(s.spoken())
    }

    fun clear() = viewModelScope.launch(Dispatchers.IO) {
        app.db.inbox().clear()
        _summary.value = null
    }

    override fun onCleared() {
        speaker?.shutdown()
        super.onCleared()
    }
}

/* ---------------- Receipts ---------------- */

class ReceiptsViewModel(application: Application) : AndroidViewModel(application) {
    val receipts = app.db.receipts().observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val settings = app.settings.state
    val busy = MutableStateFlow(false)
    val message = MutableStateFlow<String?>(null)
    val partial = MutableStateFlow("")
    private var listener: SpeechListener? = null
    private val handled = HashSet<String>()

    fun ingest(path: String, isAudio: Boolean) {
        if (!handled.add(path)) return
        viewModelScope.launch {
            busy.value = true
            message.value = if (isAudio) L.t("Transcribing voice note on-device…", "מתמלל את ההקלטה במכשיר…") else L.t("Reading receipt on-device…", "קורא את הקבלה במכשיר…")
            val out = ReceiptIngest.ingestFile(app, File(path), isAudio)
            message.value = out.message
            busy.value = false
        }
    }

    fun dictate() = viewModelScope.launch {
        val l = listener ?: SpeechListener(app).also { listener = it }
        busy.value = true
        message.value = L.t("Listening… say merchant, date and total", "מקשיב… תגיד את שם העסק, התאריך והסכום")
        val job = launch { l.partial.collect { partial.value = it } }
        val heard = l.listenOnce(timeoutMs = 30_000)
        job.cancel()
        partial.value = ""
        val text = heard.text
        message.value = if (text.isNullOrBlank()) L.t("Nothing recognized: ", "לא זוהה כלום: ") + SpeechListener.errorText(heard.error) else ReceiptIngest.ingestDictation(app, text).message
        busy.value = false
    }

    fun update(r: Receipt) = viewModelScope.launch(Dispatchers.IO) { app.db.receipts().update(r) }

    fun addManual() = viewModelScope.launch(Dispatchers.IO) {
        app.db.receipts().insert(
            Receipt(
                merchant = "", dateText = "", dateMillis = System.currentTimeMillis(), total = null, currency = "",
                source = ReceiptSource.MANUAL, rawText = "", filePath = null, sourceRef = "manual:${System.currentTimeMillis()}",
            )
        )
    }

    fun delete(r: Receipt) = viewModelScope.launch(Dispatchers.IO) {
        r.filePath?.let { File(it).delete() }
        app.db.receipts().delete(r)
    }

    fun saveImap(host: String, port: Int, user: String, password: String) {
        if (host.isBlank() || user.isBlank()) { message.value = L.t("Host and user are required", "צריך שרת ושם משתמש"); return }
        val enc = if (password.isNotEmpty()) SecretBox.encrypt(password) else null
        if (enc == null && !app.settings.current.imapPasswordSet) { message.value = L.t("Enter the app password", "צריך להזין סיסמת אפליקציה"); return }
        app.settings.setImap(host, port, user, enc)
        message.value = L.t("E-mail settings saved on this phone (password encrypted with Android Keystore)", "הגדרות הדואר נשמרו בטלפון הזה (הסיסמה מוצפנת ב-Android Keystore)")
    }

    fun clearImap() { app.settings.clearImap(); message.value = L.t("E-mail removed; e-mail import is skipped", "הדואר הוסר; לא ייבדקו קבלות מהדואר") }

    fun syncEmail() {
        if (!app.settings.current.imapPasswordSet) { message.value = L.t("E-mail not configured; skipped", "הדואר לא מוגדר; דילגתי"); return }
        ImapWorker.runNow(app)
        message.value = L.t("Checking e-mail in the background; you'll get a notification", "בודק את הדואר ברקע; תקבל התראה")
    }

    override fun onCleared() {
        listener?.destroy()
        super.onCleared()
    }
}

/* ---------------- Voice ---------------- */

class VoiceViewModel(application: Application) : AndroidViewModel(application) {
    data class Turn(val heard: String, val reply: String)

    val listening = MutableStateFlow(false)
    val partial = MutableStateFlow("")
    val level = MutableStateFlow(0f)
    val history = MutableStateFlow<List<Turn>>(emptyList())
    private var listener: SpeechListener? = null
    private var speaker: Speaker? = null

    val onDevice: Boolean get() = (listener ?: SpeechListener(app).also { listener = it }).onDevice

    fun listen() = viewModelScope.launch {
        if (listening.value) return@launch
        val l = listener ?: SpeechListener(app).also { listener = it }
        val sp = speaker ?: Speaker(app).also { speaker = it }
        sp.stop()
        listening.value = true
        AgentStatus.setListening(app, true)
        val pj = launch { l.partial.collect { partial.value = it } }
        val lj = launch { l.level.collect { level.value = it } }
        val heard = try { l.listenOnce(timeoutMs = 15_000) } finally {
            pj.cancel(); lj.cancel()
            listening.value = false
            AgentStatus.setListening(app, false)
        }
        partial.value = ""
        val text = heard.text
        val reply = if (text.isNullOrBlank()) {
            L.t("I did not hear a command: ", "לא שמעתי פקודה: ") + SpeechListener.errorText(heard.error) + "."
        } else {
            VoiceCommands.execute(app, VoiceCommands.parse(text))
        }
        history.value = (listOf(Turn(text ?: L.t("(nothing)", "(כלום)"), reply)) + history.value).take(20)
        sp.speak(reply)
    }

    fun runTyped(text: String) = viewModelScope.launch {
        val reply = VoiceCommands.execute(app, VoiceCommands.parse(text))
        history.value = (listOf(Turn(text, reply)) + history.value).take(20)
        (speaker ?: Speaker(app).also { speaker = it }).speak(reply)
    }

    override fun onCleared() {
        listener?.destroy()
        speaker?.shutdown()
        super.onCleared()
    }
}
