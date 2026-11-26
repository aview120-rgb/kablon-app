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
import com.autoagent.app.util.L
import com.autoagent.app.voice.SpeechSupport
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
    val pack by SpeechSupport.offlinePack.collectAsState()
    val voice by SpeechSupport.ttsVoice.collectAsState()
    val items = remember(tick, pack, voice) { Permissions.items(context) }
    LifecycleResumeEffect(Unit) {
        tick++
        SpeechSupport.refresh(context)
        onPauseOrDispose { }
    }
    LaunchedEffect(tick) { SpeechSupport.refreshTts(context) }
    val runtime = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        tick++
        if (it.values.any { granted -> !granted }) note = L.t("Some permissions were denied. If Android stops asking, open App info → Permissions.", "חלק מההרשאות נדחו. אם אנדרואיד מפסיק לשאול, צריך לפתוח פרטי האפליקציה ← הרשאות.")
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
                if (SpeechSupport.download(context)) {
                    note = L.t(
                        "Asked Android to download the offline speech pack for English. Come back here in a minute to see it turn green.",
                        "ביקשתי מאנדרואיד להוריד את חבילת זיהוי הדיבור בעברית. כדאי לחזור לכאן בעוד דקה ולראות שזה הפך לירוק.",
                    )
                } else {
                    note = L.t(
                        "Open voice settings (e.g. Settings → System → Languages → On-device speech recognition) and download your language.",
                        "צריך לפתוח את הגדרות הקול (למשל הגדרות ← מערכת ← שפות ← זיהוי דיבור במכשיר) ולהוריד עברית.",
                    )
                    open(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
                }
            }
            Permissions.Grant.None -> Unit
        }
    }

    val ready = items.count { it.granted }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text(L.t("$ready of ${items.size} ready", "$ready מתוך ${items.size} מוכנים"), style = MaterialTheme.typography.titleLarge, color = if (ready == items.size) Ok else Warn,
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
                    Button(onClick = { grant(p) }, modifier = Modifier.heightIn(min = 56.dp)) { Text(L.t("Grant", "אישור")) }
                }
            }
        }
        item {
            InfoCard {
                Text(L.t("What Android will not allow", "מה אנדרואיד לא מאפשר"), style = MaterialTheme.typography.titleMedium)
                Text(L.t(
                    "• Regular apps cannot play audio into the call uplink or record call audio (needs system-only CAPTURE_AUDIO_OUTPUT). AutoAgent uses speakerphone and saves the transcript; it shows why when audio is missing.",
                    "• אפליקציות רגילות לא יכולות להשמיע קול לתוך השיחה או להקליט אותה (נדרשת הרשאת מערכת CAPTURE_AUDIO_OUTPUT). AutoAgent משתמש ברמקול ושומר תמלול, ומציג את הסיבה כשאין קול.",
                ))
                Text(L.t(
                    "• Sideloaded apps on Android 13+ must allow \"restricted settings\" before Accessibility and Notification access can be turned on.",
                    "• באפליקציה שהותקנה מקובץ, באנדרואיד 13 ומעלה צריך לאשר \"הגדרות מוגבלות\" לפני שאפשר להפעיל נגישות וגישה להתראות.",
                ))
                Text(L.t(
                    "• Sending in WhatsApp/other apps needs the screen unlocked; when locked, the send is aborted and you are notified.",
                    "• שליחה בוואטסאפ ובאפליקציות אחרות דורשת מסך לא נעול; כשהמסך נעול השליחה מבוטלת ותקבל התראה.",
                ))
                Text(L.t(
                    "• Reading text from receipt photos (ML Kit) supports Latin letters only, not Hebrew. Dictate Hebrew receipts instead.",
                    "• קריאת טקסט מתמונת קבלה (ML Kit של Google) תומכת רק באותיות לטיניות, לא בעברית. קבלות בעברית עדיף להכתיב בקול.",
                ))
            }
        }
        item { Text(" ") }
    }
}
