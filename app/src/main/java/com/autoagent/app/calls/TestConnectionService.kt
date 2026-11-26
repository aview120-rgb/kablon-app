package com.autoagent.app.calls

import android.os.Handler
import android.os.Looper
import android.telecom.CallAudioState
import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.DisconnectCause
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import com.autoagent.app.AutoAgentApp

/**
 * Self-managed ConnectionService. It provides a "test line" so you can rehearse the assistant script
 * end-to-end (disclosure, questions, templates, transcript) without a real caller.
 */
class TestConnectionService : ConnectionService() {

    override fun onCreateIncomingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?,
    ): Connection {
        val conn = TestConnection()
        conn.connectionProperties = Connection.PROPERTY_SELF_MANAGED
        conn.audioModeIsVoip = true
        conn.setAddress(request?.address, TelecomManager.PRESENTATION_ALLOWED)
        conn.setCallerDisplayName("Assistant test", TelecomManager.PRESENTATION_ALLOWED)
        conn.setRinging()
        Handler(Looper.getMainLooper()).postDelayed({ conn.onAnswer() }, 500)
        return conn
    }

    override fun onCreateIncomingConnectionFailed(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?,
    ) {
        (applicationContext as AutoAgentApp).log("test-call", "Android rejected the self-managed test call", false)
    }

    private inner class TestConnection : Connection() {
        private var session: AssistantSession? = null

        override fun onShowIncomingCallUi() {
            onAnswer()
        }

        override fun onAnswer() {
            if (state == STATE_ACTIVE) return
            setActive()
            val control = object : CallControl {
                override val number: String = "TEST"
                @Suppress("DEPRECATION")
                override fun setSpeaker(on: Boolean) = setAudioRoute(if (on) CallAudioState.ROUTE_SPEAKER else CallAudioState.ROUTE_EARPIECE)
                override fun hangUp() = end(DisconnectCause.LOCAL)
                override fun isActive(): Boolean = state == STATE_ACTIVE
            }
            session = AssistantSession(this@TestConnectionService, control, isTest = true).also {
                it.start((applicationContext as AutoAgentApp).appScope)
            }
        }

        override fun onReject() = end(DisconnectCause.REJECTED)
        override fun onDisconnect() = end(DisconnectCause.LOCAL)
        override fun onAbort() = end(DisconnectCause.CANCELED)

        private fun end(cause: Int) {
            if (state == STATE_DISCONNECTED) return
            setDisconnected(DisconnectCause(cause))
            destroy()
            session?.stop()
        }
    }
}
