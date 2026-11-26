package com.autoagent.app.service

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.autoagent.app.AutoAgentApp
import com.autoagent.app.util.TimeText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Starts/stops the persistent foreground service according to what is pending. */
object AgentStatus {
    @Volatile
    var listening: Boolean = false
        private set

    suspend fun refresh(context: Context) {
        val app = context.applicationContext as AutoAgentApp
        val now = System.currentTimeMillis()
        val alarms = withContext(Dispatchers.IO) { app.db.alarms().enabled().filter { it.triggerAt > now } }
        val sends = withContext(Dispatchers.IO) { app.db.sends().pending() }
        val auto = app.settings.current.autoAnswerEnabled
        val parts = mutableListOf<String>()
        if (listening) parts += "Microphone in use for a voice command"
        if (sends.isNotEmpty()) parts += "${sends.size} scheduled message(s), next ${TimeText.format(sends.first().sendAt)}"
        if (alarms.isNotEmpty()) parts += "${alarms.size} alarm(s), next ${TimeText.format(alarms.first().triggerAt)}"
        if (auto) parts += "Auto-answer ON for unknown numbers"
        val intent = Intent(context, AgentForegroundService::class.java)
        if (parts.isEmpty()) {
            context.stopService(intent)
            return
        }
        intent.putExtra(AgentForegroundService.EXTRA_TEXT, parts.joinToString(" · "))
            .putExtra(AgentForegroundService.EXTRA_LISTENING, listening)
        try {
            ContextCompat.startForegroundService(context, intent)
        } catch (e: Exception) {
            // Android blocks foreground-service starts from some background states; it is retried when the app opens.
            app.log("service", "Could not show the status notification from background: ${e.javaClass.simpleName}", false)
        }
    }

    suspend fun setListening(context: Context, on: Boolean) {
        listening = on
        refresh(context)
    }
}
