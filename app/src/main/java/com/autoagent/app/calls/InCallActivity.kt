package com.autoagent.app.calls

import com.autoagent.app.util.L
import android.os.Build
import android.os.Bundle
import android.telecom.Call
import android.telecom.CallAudioState
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autoagent.app.ui.theme.AutoAgentTheme

class InCallActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        setContent {
            AutoAgentTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val calls by CallManager.calls.collectAsState()
                    LaunchedEffect(calls.isEmpty()) { if (calls.isEmpty()) finish() }
                    InCallScreen(calls)
                }
            }
        }
    }
}

@Composable
private fun InCallScreen(calls: List<CallManager.CallUi>) {
    val assistant by CallManager.assistantStatus.collectAsState()
    val audio by CallManager.audioState.collectAsState()
    Column(
        Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        assistant?.let {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(L.t("Assistant is handling this call", "העוזר מטפל בשיחה הזו"), style = MaterialTheme.typography.titleMedium, color = Color(0xFFFFC857))
                    Text(it, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        calls.forEach { c -> CallCard(c, audio) }
    }
}

@Composable
private fun CallCard(c: CallManager.CallUi, audio: CallAudioState?) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(c.name ?: c.number.ifBlank { L.t("Unknown number", "מספר לא ידוע") }, fontSize = 30.sp)
            if (c.name != null) Text(c.number, style = MaterialTheme.typography.bodyLarge)
            Text(CallManager.stateLabel(c.state), style = MaterialTheme.typography.titleMedium)
            val big = Modifier.fillMaxWidth().height(64.dp)
            when (c.state) {
                Call.STATE_RINGING -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = { CallManager.answer(c.id) },
                        modifier = Modifier.weight(1f).height(72.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                    ) { Text(L.t("Answer", "מענה"), fontSize = 20.sp) }
                    Button(
                        onClick = { CallManager.reject(c.id) },
                        modifier = Modifier.weight(1f).height(72.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828)),
                    ) { Text(L.t("Reject", "דחייה"), fontSize = 20.sp) }
                }
                else -> {
                    val speakerOn = audio?.route == CallAudioState.ROUTE_SPEAKER
                    val muted = audio?.isMuted == true
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = { CallManager.setSpeaker(!speakerOn) }, modifier = Modifier.weight(1f).height(64.dp)) {
                            Text(if (speakerOn) L.t("Speaker ON", "רמקול פועל") else L.t("Speaker", "רמקול"))
                        }
                        OutlinedButton(onClick = { CallManager.setMuted(!muted) }, modifier = Modifier.weight(1f).height(64.dp)) {
                            Text(if (muted) L.t("Unmute", "בטל השתקה") else L.t("Mute", "השתק"))
                        }
                    }
                    if (c.state == Call.STATE_HOLDING) {
                        OutlinedButton(onClick = { CallManager.unhold(c.id) }, modifier = big) { Text(L.t("Resume", "המשך"), fontSize = 18.sp) }
                    } else if (c.state == Call.STATE_ACTIVE) {
                        OutlinedButton(onClick = { CallManager.hold(c.id) }, modifier = big) { Text(L.t("Hold", "המתנה"), fontSize = 18.sp) }
                    }
                    Button(
                        onClick = { CallManager.hangUp(c.id) },
                        modifier = big,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828)),
                    ) { Text(L.t("Hang up", "ניתוק"), fontSize = 20.sp) }
                }
            }
        }
    }
}
