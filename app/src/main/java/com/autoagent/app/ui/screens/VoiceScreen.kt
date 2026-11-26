package com.autoagent.app.ui.screens

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.autoagent.app.ui.BigOutlined
import com.autoagent.app.ui.InfoCard
import com.autoagent.app.ui.SectionTitle
import com.autoagent.app.ui.VoiceViewModel
import com.autoagent.app.ui.theme.Warn

@Composable
fun VoiceScreen(vm: VoiceViewModel) {
    val listening by vm.listening.collectAsState()
    val partial by vm.partial.collectAsState()
    val history by vm.history.collectAsState()
    var typed by remember { mutableStateOf("") }
    val onDevice = remember { vm.onDevice }
    val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) vm.listen() }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Box(Modifier.fillMaxWidth().padding(top = 24.dp), contentAlignment = Alignment.Center) {
                Button(
                    onClick = { mic.launch(Manifest.permission.RECORD_AUDIO) },
                    shape = CircleShape,
                    modifier = Modifier.size(150.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = if (listening) Color(0xFFC62828) else MaterialTheme.colorScheme.primary),
                ) { Icon(Icons.Filled.Mic, contentDescription = "Tap to speak", modifier = Modifier.size(72.dp)) }
            }
        }
        item {
            Text(
                if (listening) "Listening… ${partial}" else "Tap the mic and speak",
                style = MaterialTheme.typography.titleMedium, modifier = Modifier.fillMaxWidth(),
            )
            if (!onDevice) Text("On-device recognizer not found: install the offline language pack (Permissions) so commands work without network.", color = Warn)
        }
        item {
            InfoCard {
                Text("Offline commands", style = MaterialTheme.typography.titleMedium)
                Text("• Set alarm for 7:30  /  wake me up in 20 minutes")
                Text("• Call 050 123 4567  /  call Dana")
                Text("• Schedule SMS to Dana at 6 pm saying I'm on my way")
                Text("• Read my summaries")
                Text("• Turn auto-answer on / off")
            }
        }
        item {
            OutlinedTextField(typed, { typed = it }, label = { Text("Or type a command") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            BigOutlined("Run", Modifier.fillMaxWidth().padding(top = 8.dp), enabled = typed.isNotBlank()) { vm.runTyped(typed); typed = "" }
        }
        if (history.isNotEmpty()) item { SectionTitle("Recent") }
        items(history) { t ->
            InfoCard {
                Text("You: ${t.heard}", style = MaterialTheme.typography.bodyLarge)
                Text("AutoAgent: ${t.reply}", style = MaterialTheme.typography.bodyMedium)
            }
        }
        item { Text(" ") }
    }
}
