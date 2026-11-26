package com.autoagent.app.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.autoagent.app.ui.theme.AutoAgentTheme
import com.autoagent.app.util.TimeText

class AlarmActivity : ComponentActivity() {
    private val finished = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        ContextCompat.registerReceiver(
            this, finished, IntentFilter(AlarmRingService.ACTION_FINISHED), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        val label = intent.getStringExtra(AlarmRingService.EXTRA_LABEL) ?: "Alarm"
        setContent {
            AutoAgentTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(
                        Modifier.fillMaxSize().padding(32.dp),
                        verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(TimeText.clock(System.currentTimeMillis()), fontSize = 72.sp)
                        Text(label, style = MaterialTheme.typography.headlineSmall)
                        Button(
                            onClick = { send(AlarmRingService.ACTION_DISMISS, label) },
                            modifier = Modifier.fillMaxWidth().height(72.dp),
                        ) { Text("Dismiss", fontSize = 22.sp) }
                        OutlinedButton(
                            onClick = { send(AlarmRingService.ACTION_SNOOZE, label) },
                            modifier = Modifier.fillMaxWidth().height(72.dp),
                        ) { Text("Snooze 10 min", fontSize = 22.sp) }
                    }
                }
            }
        }
    }

    private fun send(action: String, label: String) {
        startService(Intent(this, AlarmRingService::class.java).setAction(action).putExtra(AlarmRingService.EXTRA_LABEL, label))
        finish()
    }

    override fun onDestroy() {
        unregisterReceiver(finished)
        super.onDestroy()
    }
}
