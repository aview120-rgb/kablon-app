package com.autoagent.app.ui.screens

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.autoagent.app.ui.InfoCard
import com.autoagent.app.ui.Permissions
import com.autoagent.app.ui.StatusDot
import com.autoagent.app.ui.theme.Ok
import com.autoagent.app.ui.theme.Warn
import java.util.Locale

@Composable
fun PermissionsScreen() {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    var note by remember { mutableStateOf<String?>(null) }
    val items = remember(tick) { Permissions.items(context) }
    LifecycleResumeEffect(Unit) {
        tick++
        onPauseOrDispose { }
    }
    val runtime = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        tick++
        if (it.values.any { granted -> !granted }) note = "Some permissions were denied. If Android stops asking, open App info → Permissions."
    }
    val forResult = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { tick++ }

    fun open(intent: Intent) {
        try {
            forResult.launch(intent)
        } catch (_: ActivityNotFoundException) {
            forResult.launch(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
        }
    }

    fun grant(item: Permissions.Item) {
        when (val g = item.grant) {
            is Permissions.Grant.Runtime -> runtime.launch(g.permissions.toTypedArray())
            is Permissions.Grant.Screen -> open(g.intent)
            Permissions.Grant.DialerRole -> open(Permissions.dialerRoleIntent(context))
            Permissions.Grant.SpeechPack -> {
                if (Build.VERSION.SDK_INT >= 33 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
                    val r = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                    r.triggerModelDownload(
                        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                    )
                    r.destroy()
                    note = "Asked Android to download the offline speech pack for ${Locale.getDefault().displayLanguage}."
                } else {
                    note = "Open your keyboard/voice settings (e.g. Settings → System → Languages → On-device speech recognition) and download your language."
                    open(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
                }
            }
            Permissions.Grant.None -> Unit
        }
    }

    val ready = items.count { it.granted }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text("$ready of ${items.size} ready", style = MaterialTheme.typography.titleLarge, color = if (ready == items.size) Ok else Warn,
                modifier = Modifier.padding(top = 12.dp))
            note?.let { Text(it, color = Warn) }
        }
        items(items, key = { it.key }) { p ->
            InfoCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatusDot(p.granted)
                    Column(Modifier.weight(1f)) {
                        Text(p.title, style = MaterialTheme.typography.titleMedium)
                        Text(p.why, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (!p.granted && p.grant != Permissions.Grant.None) {
                    Button(onClick = { grant(p) }, modifier = Modifier.heightIn(min = 56.dp)) { Text("Grant") }
                }
            }
        }
        item {
            InfoCard {
                Text("What Android will not allow", style = MaterialTheme.typography.titleMedium)
                Text("• Regular apps cannot play audio into the call uplink or record call audio (needs system-only CAPTURE_AUDIO_OUTPUT). AutoAgent uses speakerphone and saves the transcript; it shows why when audio is missing.")
                Text("• Sideloaded apps on Android 13+ must allow \"restricted settings\" before Accessibility and Notification access can be turned on.")
                Text("• Sending in WhatsApp/other apps needs the screen unlocked; when locked, the send is aborted and you are notified.")
            }
        }
        item { Text(" ") }
    }
}
