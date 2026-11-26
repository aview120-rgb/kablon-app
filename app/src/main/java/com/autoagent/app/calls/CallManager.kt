package com.autoagent.app.calls

import com.autoagent.app.util.L
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.VideoProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Holds the live Telecom calls handed to our InCallService and exposes them to the UI. */
object CallManager {
    data class CallUi(
        val id: String,
        val number: String,
        val name: String?,
        val state: Int,
        val incoming: Boolean,
        val assistant: Boolean,
    )

    private val live = LinkedHashMap<String, Call>()
    private val names = HashMap<String, String?>()
    private val assisted = HashSet<String>()
    private val _calls = MutableStateFlow<List<CallUi>>(emptyList())
    val calls: StateFlow<List<CallUi>> = _calls.asStateFlow()
    val assistantStatus = MutableStateFlow<String?>(null)
    val audioState = MutableStateFlow<CallAudioState?>(null)

    @Volatile
    var service: AgentInCallService? = null

    fun idOf(call: Call): String = System.identityHashCode(call).toString()

    @Suppress("DEPRECATION")
    fun stateOf(call: Call): Int = if (android.os.Build.VERSION.SDK_INT >= 31) call.details.state else call.state

    fun numberOf(call: Call): String = call.details?.handle?.schemeSpecificPart.orEmpty()

    fun isIncoming(call: Call): Boolean =
        if (android.os.Build.VERSION.SDK_INT >= 29) call.details.callDirection == Call.Details.DIRECTION_INCOMING
        else stateOf(call) == Call.STATE_RINGING

    @Synchronized
    fun add(call: Call, incoming: Boolean) {
        live[idOf(call)] = call
        if (incoming) incomingIds += idOf(call)
        publish()
    }

    private val incomingIds = HashSet<String>()

    @Synchronized
    fun remove(call: Call) {
        val id = idOf(call)
        live.remove(id); names.remove(id); assisted.remove(id); incomingIds.remove(id)
        publish()
    }

    @Synchronized
    fun setName(call: Call, name: String?) { names[idOf(call)] = name; publish() }

    @Synchronized
    fun markAssisted(call: Call) { assisted += idOf(call); publish() }

    @Synchronized
    fun publish() {
        _calls.value = live.map { (id, c) ->
            CallUi(id, numberOf(c), names[id], stateOf(c), id in incomingIds, id in assisted)
        }
    }

    @Synchronized
    fun find(id: String): Call? = live[id]

    @Synchronized
    fun count(): Int = live.size

    fun answer(id: String) = find(id)?.answer(VideoProfile.STATE_AUDIO_ONLY)
    fun reject(id: String) = find(id)?.reject(false, null)
    fun hold(id: String) = find(id)?.hold()
    fun unhold(id: String) = find(id)?.unhold()
    fun hangUp(id: String) = find(id)?.disconnect()

    @Suppress("DEPRECATION")
    fun setSpeaker(on: Boolean) {
        service?.setAudioRoute(if (on) CallAudioState.ROUTE_SPEAKER else CallAudioState.ROUTE_WIRED_OR_EARPIECE)
    }

    fun setMuted(muted: Boolean) { service?.setMuted(muted) }

    fun stateLabel(state: Int): String = when (state) {
        Call.STATE_RINGING -> L.t("Ringing", "מצלצל")
        Call.STATE_DIALING -> L.t("Dialing", "מחייג")
        Call.STATE_CONNECTING -> L.t("Connecting", "מתחבר")
        Call.STATE_ACTIVE -> L.t("Active", "פעילה")
        Call.STATE_HOLDING -> L.t("On hold", "בהמתנה")
        Call.STATE_DISCONNECTING -> L.t("Ending", "מסתיימת")
        Call.STATE_DISCONNECTED -> L.t("Ended", "הסתיימה")
        Call.STATE_SELECT_PHONE_ACCOUNT -> L.t("Choose SIM", "בחירת SIM")
        else -> L.t("State $state", "מצב $state")
    }
}
