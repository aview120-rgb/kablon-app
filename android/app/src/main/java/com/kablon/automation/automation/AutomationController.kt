package com.kablon.automation.automation

import android.content.Context
import com.kablon.automation.core.Notifier
import com.kablon.automation.core.StepStatus
import com.kablon.automation.core.WorkflowLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * מנהל הרצת זרימות: התחלה, השהיה, המשך, עצירה מיידית, ובקשת התערבות ידנית.
 */
object AutomationController {
    enum class State { IDLE, RUNNING, PAUSED, NEEDS_USER, FINISHED, FAILED, STOPPED }

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state.asStateFlow()
    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    @Volatile var currentWorkflow: String = ""
        private set
    private var appContext: Context? = null

    fun isBusy() = job?.isActive == true

    /**
     * מתחיל זרימה. מחזיר הודעת שגיאה אם לא ניתן להתחיל, או null בהצלחה.
     */
    fun start(context: Context, name: String, block: suspend Automation.() -> Unit): String? {
        if (isBusy()) return "זרימה אחרת כבר רצה (${currentWorkflow}). עצור אותה קודם."
        val svc = KablonAccessibilityService.instance
            ?: return "שירות הנגישות של Kablon לא פעיל. הפעל אותו במסך הראשי."
        appContext = context.applicationContext
        currentWorkflow = name
        _state.value = State.RUNNING
        _status.value = "מתחיל: $name"
        WorkflowLog.add(name, "התחלת זרימה", StepStatus.START)
        job = scope.launch {
            try {
                Automation(svc, name).block()
                _state.value = State.FINISHED
                _status.value = "הסתיים: $name"
                WorkflowLog.add(name, "הזרימה הסתיימה", StepStatus.OK)
                Notifier.info(svc, "Kablon", "הזרימה \"$name\" הסתיימה")
            } catch (e: CancellationException) {
                _state.value = State.STOPPED
                _status.value = "נעצר על ידי המשתמש"
                WorkflowLog.add(name, "נעצר על ידי המשתמש", StepStatus.STOP)
            } catch (e: Throwable) {
                _state.value = State.FAILED
                _status.value = "שגיאה: ${e.message}"
                WorkflowLog.add(name, "הזרימה נכשלה", StepStatus.FAIL, e.message ?: e.javaClass.simpleName)
                Notifier.manualNeeded(svc, "הזרימה \"$name\" נכשלה: ${e.message}")
            } finally {
                Notifier.cancelManual(svc)
                scheduleIdle()
            }
        }
        return null
    }

    private fun scheduleIdle() {
        scope.launch {
            delay(5000)
            val s = _state.value
            if (!isBusy() && (s == State.FINISHED || s == State.FAILED || s == State.STOPPED)) {
                _state.value = State.IDLE
            }
        }
    }

    fun pause() {
        if (_state.value == State.RUNNING) {
            _state.value = State.PAUSED
            _status.value = "מושהה – לחץ המשך"
            WorkflowLog.add(currentWorkflow, "השהיה", StepStatus.INFO)
        }
    }

    fun resume() {
        val s = _state.value
        if (s == State.PAUSED || s == State.NEEDS_USER) {
            appContext?.let { Notifier.cancelManual(it) }
            _state.value = State.RUNNING
            _status.value = "ממשיך…"
        }
    }

    /** עצירה מיידית – מבטל את הזרימה בכל נקודה. */
    fun stop() {
        val j = job
        if (j != null && j.isActive) j.cancel()
        else if (_state.value != State.IDLE) _state.value = State.IDLE
    }

    fun setStatus(text: String) {
        _status.value = text
    }

    /** נקודת בדיקה: זורק ביטול אם נעצר, ומחכה אם מושהה. */
    suspend fun checkpoint() {
        currentCoroutineContext().ensureActive()
        if (_state.value == State.PAUSED || _state.value == State.NEEDS_USER) {
            _state.first { it == State.RUNNING }
        }
        currentCoroutineContext().ensureActive()
    }

    /** מבקש מהמשתמש להשלים ידנית, ומחכה עד שילחץ "המשך" בחלונית הצפה. */
    suspend fun requestUser(message: String) {
        _state.value = State.NEEDS_USER
        _status.value = message
        WorkflowLog.add(currentWorkflow, "נדרשת התערבות ידנית", StepStatus.MANUAL, message)
        appContext?.let { Notifier.manualNeeded(it, message) }
        _state.first { it == State.RUNNING }
        currentCoroutineContext().ensureActive()
    }
}
