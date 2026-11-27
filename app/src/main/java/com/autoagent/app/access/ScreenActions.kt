package com.autoagent.app.access

import android.content.Context
import android.os.PowerManager
import android.telecom.TelecomManager
import com.autoagent.app.AutoAgentApp
import com.autoagent.app.util.L
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The app-wide screen action queue. Everything that touches other apps' screens goes through here, so
 * scheduled sends, server tasks and retries never overlap. Actions wait while a phone call is active.
 */
object ScreenActions {
    @Volatile private var queue: SerialActionQueue? = null

    fun get(context: Context): SerialActionQueue {
        queue?.let { return it }
        synchronized(this) {
            queue?.let { return it }
            val app = context.applicationContext as AutoAgentApp
            return SerialActionQueue(app.appScope, busyReason = { busyReason(app) }).also { queue = it }
        }
    }

    private fun busyReason(context: Context): String? {
        // Without READ_PHONE_STATE the call state is unknown; then actions are not delayed.
        val canRead = androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_PHONE_STATE) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        val inCall = canRead && runCatching {
            context.getSystemService(TelecomManager::class.java).isInCall
        }.getOrDefault(false)
        if (inCall) return L.t("A phone call is active", "יש שיחת טלפון פעילה")
        return null
    }

    /** Runs a send/prepare on the main thread inside the queue. */
    suspend fun <T> runOnService(
        context: Context,
        label: String,
        priority: SerialActionQueue.Priority = SerialActionQueue.Priority.NORMAL,
        timeoutMs: Long = 90_000,
        block: suspend (AutomationService) -> T,
    ): SerialActionQueue.Result<T> = get(context).run(label, priority, timeoutMs) {
        val svc = AutomationService.instance ?: throw IllegalStateException(L.t("Accessibility service disconnected", "שירות הנגישות התנתק"))
        val pm = context.getSystemService(PowerManager::class.java)
        val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "autoagent:screen-action")
        wl.acquire(timeoutMs)
        try {
            withContext(Dispatchers.Main) { block(svc) }
        } finally {
            if (wl.isHeld) wl.release()
        }
    }
}
