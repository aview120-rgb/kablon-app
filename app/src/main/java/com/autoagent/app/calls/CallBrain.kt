package com.autoagent.app.calls

import com.autoagent.app.data.KeywordRule
import java.util.Locale

/** Offline, rule-based replies for the call assistant. No cloud model. */
object CallBrain {
    const val GREETING = "This call is answered by an assistant and is recorded."
    const val ASK = "What do you need? Please tell me now."
    const val UNKNOWN_REPLY = "I will pass this to the owner."
    const val ANYTHING_ELSE = "Anything else?"
    const val CLOSING = "Thank you. The owner will call you back. Goodbye."
    const val NOT_HEARD = "Sorry, I did not catch that. Please say it again."
    const val UNKNOWN = "UNKNOWN"

    data class Reply(val intent: String, val text: String)

    fun reply(utterance: String, rules: List<KeywordRule>): Reply {
        val u = " " + utterance.lowercase(Locale.ROOT).replace(Regex("""[^\p{L}\p{N}' ]"""), " ").replace(Regex("\\s+"), " ") + " "
        for (rule in rules.filter { it.enabled }.sortedByDescending { it.priority }) {
            val hit = rule.keywords.split(',')
                .map { it.trim().lowercase(Locale.ROOT) }
                .filter { it.isNotEmpty() }
                .any { u.contains(" $it ") }
            if (hit) return Reply(rule.intent, rule.response)
        }
        return Reply(UNKNOWN, UNKNOWN_REPLY)
    }
}
