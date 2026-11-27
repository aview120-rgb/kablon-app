package com.autoagent.app.access

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.autoagent.app.AutoAgentApp
import com.autoagent.app.R
import com.autoagent.app.priv.CallBridge
import com.autoagent.app.util.L
import com.autoagent.app.util.Notifier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Tracks whether the accessibility service is really usable and tries to recover it.
 *
 * - DISABLED: switched off in Android settings (only you can switch it on; or Shizuku can, without root).
 * - ENABLED_NOT_BOUND: on in settings but Android has not connected it (crashed, killed, or "restricted settings"
 *   on a sideloaded APK). Recovery: toggle it through Shizuku if available, otherwise a notification with a
 *   one-tap shortcut to the setting.
 * - READY: connected and answering.
 */
object AccessibilityHealth {
    enum class State { DISABLED, ENABLED_NOT_BOUND, READY }

    data class Health(
        val state: State = State.DISABLED,
        val connectedAt: Long = 0,
        val lastEventAt: Long = 0,
        val lastError: String = "",
        val recoveries: Int = 0,
    ) {
        val ready: Boolean get() = state == State.READY

        fun label(): String = when (state) {
            State.READY -> L.t("Active", "פעיל")
            State.ENABLED_NOT_BOUND -> L.t("Not responding", "לא מגיב")
            State.DISABLED -> L.t("Off", "כבוי")
        }

        fun code(): String = state.name.lowercase()
    }

    private val _health = MutableStateFlow(Health())
    val health: StateFlow<Health> = _health.asStateFlow()

    private const val NOTIFY_ID = 4711
    private var lastRecoveryAt = 0L

    fun component(context: Context) = ComponentName(context, AutomationService::class.java)

    fun isEnabledInSettings(context: Context): Boolean {
        val flat = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val me = component(context)
        return flat.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    /** Re-reads the real state (settings + whether the service instance is connected). */
    fun refresh(context: Context): Health {
        val enabled = isEnabledInSettings(context)
        val bound = AutomationService.instance != null
        val state = when {
            bound -> State.READY
            enabled -> State.ENABLED_NOT_BOUND
            else -> State.DISABLED
        }
        _health.value = _health.value.copy(state = state)
        if (state == State.READY) NotificationManagerCompat.from(context).cancel(NOTIFY_ID)
        return _health.value
    }

    internal fun onConnected(context: Context) {
        _health.value = _health.value.copy(state = State.READY, connectedAt = System.currentTimeMillis(), lastError = "")
        NotificationManagerCompat.from(context).cancel(NOTIFY_ID)
        (context.applicationContext as? AutoAgentApp)?.log("access", L.t("Accessibility service connected", "שירות הנגישות התחבר"), true)
    }

    internal fun onDisconnected(context: Context) {
        refresh(context)
    }

    internal fun onEvent() {
        _health.value = _health.value.copy(lastEventAt = System.currentTimeMillis())
    }

    internal fun onError(where: String, t: Throwable) {
        _health.value = _health.value.copy(lastError = "$where: ${t.javaClass.simpleName} ${t.message ?: ""}".take(300))
    }

    /** Waits up to [timeoutMs] for the service to be connected (e.g. right after boot). */
    suspend fun awaitReady(context: Context, timeoutMs: Long): AutomationService? {
        AutomationService.instance?.let { return it }
        refresh(context)
        return withTimeoutOrNull(timeoutMs) {
            health.first { it.ready }
            AutomationService.instance
        }
    }

    /**
     * Tries to bring the service back without root. Returns a Hebrew/English description of what was done.
     * Rate-limited to once per 5 minutes so it never loops.
     */
    fun recover(context: Context, force: Boolean = false): String {
        val h = refresh(context)
        if (h.ready) return L.t("Accessibility service is active", "שירות הנגישות פעיל")
        val now = System.currentTimeMillis()
        if (!force && now - lastRecoveryAt < 5 * 60_000) return L.t("Recovery attempted recently", "ניסיון התאוששות בוצע לאחרונה")
        lastRecoveryAt = now
        _health.value = _health.value.copy(recoveries = h.recoveries + 1)
        val viaShizuku = CallBridge.restartAccessibility(component(context).flattenToString())
        val app = context.applicationContext as? AutoAgentApp
        return if (viaShizuku == null) {
            notifyNeedsUser(context, h.state)
            val msg = if (h.state == State.DISABLED) L.t("Accessibility is off; asked you to turn it on", "הנגישות כבויה; נשלחה בקשה להפעלה")
            else L.t("Accessibility is on but not responding; asked you to toggle it", "הנגישות מופעלת אבל לא מגיבה; נשלחה בקשה לכבות ולהדליק")
            app?.log("access", msg, false)
            msg
        } else {
            val msg = if (viaShizuku.isEmpty()) L.t("Restarted accessibility through Shizuku", "שירות הנגישות הופעל מחדש דרך Shizuku")
            else L.t("Shizuku restart failed: $viaShizuku", "הפעלה מחדש דרך Shizuku נכשלה: $viaShizuku")
            app?.log("access", msg, viaShizuku.isEmpty())
            if (viaShizuku.isNotEmpty()) notifyNeedsUser(context, h.state)
            msg
        }
    }

    fun settingsIntent(): Intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun notifyNeedsUser(context: Context, state: State) {
        if (!Notifier.canPost(context)) return
        val pi = android.app.PendingIntent.getActivity(
            context, NOTIFY_ID, settingsIntent(),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        val text = if (state == State.DISABLED) {
            L.t("Scheduled app messages need it. Tap to open Accessibility settings and turn on AutoAgent.",
                "הודעות מתוזמנות באפליקציות צריכות אותו. הקש כדי לפתוח את הגדרות הנגישות ולהפעיל את AutoAgent.")
        } else {
            L.t("Android stopped the service. Tap, then turn AutoAgent off and on again.",
                "אנדרואיד עצר את השירות. הקש, ואז כבה והדלק מחדש את AutoAgent.")
        }
        val n = NotificationCompat.Builder(context, Notifier.CH_EVENTS)
            .setSmallIcon(R.drawable.ic_stat_agent)
            .setContentTitle(L.t("Accessibility service needs attention", "שירות הנגישות דורש טיפול"))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        try { NotificationManagerCompat.from(context).notify(NOTIFY_ID, n) } catch (_: SecurityException) {}
    }
}
