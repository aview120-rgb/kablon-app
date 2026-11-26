package com.autoagent.app.voice

import android.content.Context
import com.autoagent.app.AutoAgentApp
import com.autoagent.app.alarm.AlarmScheduler
import com.autoagent.app.calls.PhoneAccounts
import com.autoagent.app.data.AlarmEntity
import com.autoagent.app.data.ScheduledSend
import com.autoagent.app.data.SendChannel
import com.autoagent.app.inbox.Summarizer
import com.autoagent.app.schedule.SendScheduler
import com.autoagent.app.schedule.SmsSender
import com.autoagent.app.service.AgentStatus
import com.autoagent.app.util.Contacts
import com.autoagent.app.util.TimeText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/** Offline command grammar. Everything here works without network. */
sealed interface Command {
    data class SetAlarm(val at: Long, val label: String) : Command
    data class CallNumber(val target: String) : Command
    data class ScheduleSms(val target: String, val at: Long, val text: String) : Command
    data object ReadSummaries : Command
    data class ToggleAutoAnswer(val on: Boolean) : Command
    data object Help : Command
    data class Unknown(val heard: String) : Command
}

object VoiceCommands {
    fun parse(raw: String): Command {
        val s = raw.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
        if (s.isEmpty()) return Command.Unknown(raw)

        Regex("""(?:turn|switch|set)\s+(on|off)\s+(?:the\s+)?auto[\s-]?answer""").find(s)?.let {
            return Command.ToggleAutoAnswer(it.groupValues[1] == "on")
        }
        Regex("""auto[\s-]?answer(?:ing)?\s+(on|off)""").find(s)?.let { return Command.ToggleAutoAnswer(it.groupValues[1] == "on") }
        Regex("""(enable|disable|start|stop)\s+(?:the\s+)?auto[\s-]?answer""").find(s)?.let {
            return Command.ToggleAutoAnswer(it.groupValues[1] in setOf("enable", "start"))
        }

        Regex("""(?:schedule|send)\s+(?:an?\s+)?(?:sms|text|message)\s+to\s+(.+?)\s+((?:at|in|tomorrow at)\s+.+?)\s+(?:saying|that says|say|with text|:)\s+(.+)""").find(s)?.let { m ->
            val at = TimeText.parse(m.groupValues[2].removePrefix("at ").trim()) ?: return Command.Unknown(raw)
            val textStart = raw.lowercase(Locale.ROOT).indexOf(m.groupValues[3].take(12)).takeIf { it >= 0 }
            val text = textStart?.let { raw.substring(it).trim() } ?: m.groupValues[3]
            return Command.ScheduleSms(m.groupValues[1].trim(), at, text)
        }

        Regex("""(?:set\s+(?:an?\s+)?alarm|wake me(?:\s+up)?|alarm)\s+(?:for|at|in)?\s*(.+)""").find(s)?.let { m ->
            val spec = m.groupValues[1].let { if (s.contains(" in ") && !it.startsWith("in ")) "in $it" else it }
            val at = TimeText.parse(spec) ?: TimeText.parse(m.groupValues[1]) ?: return Command.Unknown(raw)
            return Command.SetAlarm(at, "Voice alarm")
        }

        Regex("""^(?:call|dial|phone)\s+(.+)""").find(s)?.let { return Command.CallNumber(it.groupValues[1].trim()) }

        if (Regex("""summar|what did i miss|read (?:my )?(?:messages|notifications|inbox)""").containsMatchIn(s)) return Command.ReadSummaries
        if (s.contains("help") || s.contains("what can you do")) return Command.Help
        return Command.Unknown(raw)
    }

    private fun resolveNumber(context: Context, target: String): Pair<String?, String> {
        val digits = target.filter { it.isDigit() || it == '+' }
        if (digits.count { it.isDigit() } >= 3 && target.count { it.isLetter() } == 0) return digits to digits
        val matches = Contacts.findByName(context, target)
        return when {
            matches.isEmpty() -> null to "I could not find $target in your contacts."
            matches.size > 1 && matches.map { it.name.lowercase() }.distinct().size > 1 ->
                null to "I found several contacts: ${matches.take(3).joinToString { it.name }}. Please say the full name."
            else -> matches.first().number to matches.first().name
        }
    }

    /** Executes a command and returns the sentence to speak back. */
    suspend fun execute(context: Context, cmd: Command): String {
        val app = context.applicationContext as AutoAgentApp
        return when (cmd) {
            is Command.SetAlarm -> {
                val id = withContext(Dispatchers.IO) { app.db.alarms().insert(AlarmEntity(triggerAt = cmd.at, label = cmd.label)) }
                if (AlarmScheduler.schedule(context, AlarmEntity(id = id, triggerAt = cmd.at, label = cmd.label))) {
                    AgentStatus.refresh(context)
                    app.log("voice", "Alarm set for ${TimeText.full(cmd.at)}", true)
                    "Alarm set for ${TimeText.format(cmd.at)}."
                } else {
                    "I saved the alarm, but exact alarms are not allowed. Turn on Alarms and reminders in the Permissions screen."
                }
            }
            is Command.CallNumber -> {
                val (number, label) = withContext(Dispatchers.IO) { resolveNumber(context, cmd.target) }
                if (number == null) label else {
                    val err = PhoneAccounts.placeCall(context, number)
                    if (err == null) { app.log("voice", "Calling $label", true); "Calling $label." } else err
                }
            }
            is Command.ScheduleSms -> {
                if (!SmsSender.hasPermission(context)) return "SMS permission is off. Grant it in the Permissions screen."
                val (number, label) = withContext(Dispatchers.IO) { resolveNumber(context, cmd.target) }
                if (number == null) return label
                val res = SendScheduler.create(
                    context,
                    ScheduledSend(
                        channel = SendChannel.SMS, packageName = "sms", appLabel = "SMS",
                        recipient = number, recipientLabel = label, text = cmd.text, sendAt = cmd.at,
                    ),
                )
                when (res) {
                    is SendScheduler.CreateResult.Ok -> "SMS to $label scheduled for ${TimeText.format(cmd.at)}: ${cmd.text}"
                    is SendScheduler.CreateResult.Rejected -> res.reason
                }
            }
            Command.ReadSummaries -> {
                val entries = withContext(Dispatchers.IO) { app.db.inbox().since(System.currentTimeMillis() - 86_400_000L) }
                Summarizer.summarize(entries).spoken()
            }
            is Command.ToggleAutoAnswer -> {
                app.settings.setAutoAnswer(cmd.on)
                AgentStatus.refresh(context)
                app.log("voice", "Auto-answer turned ${if (cmd.on) "on" else "off"}", true)
                if (cmd.on) "Auto-answer is on for numbers that are not in your contacts." else "Auto-answer is off."
            }
            Command.Help -> "You can say: set alarm for 7 30. Call 050 123 4567, or call a contact name. " +
                "Schedule SMS to Dana at 6 pm saying I am on my way. Read my summaries. Turn auto-answer on or off."
            is Command.Unknown -> "Sorry, I did not understand: ${cmd.heard}. Say help to hear the commands."
        }
    }
}
