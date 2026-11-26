package com.autoagent.app.ui.screens

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.speech.tts.TextToSpeech
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import com.autoagent.app.AutoAgentApp
import com.autoagent.app.priv.CallBridge
import com.autoagent.app.ui.InfoCard
import com.autoagent.app.ui.Permissions
import com.autoagent.app.ui.StatusDot
import com.autoagent.app.ui.theme.Ok
import com.autoagent.app.ui.theme.Warn
import com.autoagent.app.util.L
import com.autoagent.app.voice.SpeechSupport

@Composable
fun PermissionsScreen() {
    val context = LocalContext.current
    val app = context.applicationContext as AutoAgentApp
    val settings by app.settings.state.collectAsState()
    var tick by remember { mutableIntStateOf(0) }
    var note by remember { mutableStateOf<String?>(null) }
    var guide by remember { mutableStateOf<Permissions.Grant?>(null) }
    val pack by SpeechSupport.offlinePack.collectAsState()
    val voice by SpeechSupport.ttsVoice.collectAsState()
    val bridge by CallBridge.state.collectAsState()
    val items = remember(tick, pack, voice, bridge) { Permissions.items(context) }
    LifecycleResumeEffect(Unit) {
        tick++
        SpeechSupport.refresh(context)
        CallBridge.refresh()
        onPauseOrDispose { }
    }
    LaunchedEffect(tick) { SpeechSupport.refreshTts(context) }
    val runtime = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        tick++
        if (it.values.any { granted -> !granted }) note = L.t(
            "Some permissions were denied. If Android stops asking, open App info → Permissions.",
            "חלק מההרשאות נדחו. אם אנדרואיד מפסיק לשאול, צריך לפתוח פרטי האפליקציה ← הרשאות.",
        )
    }
    val forResult = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { tick++ }

    fun open(intent: Intent): Boolean = try {
        forResult.launch(intent); true
    } catch (_: ActivityNotFoundException) {
        false
    }

    fun openOrAppInfo(intent: Intent) {
        if (!open(intent)) open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
    }

    fun grant(item: Permissions.Item) {
        when (val g = item.grant) {
            is Permissions.Grant.Runtime -> runtime.launch(g.permissions.toTypedArray())
            is Permissions.Grant.Screen -> openOrAppInfo(g.intent)
            Permissions.Grant.DialerRole -> openOrAppInfo(Permissions.dialerRoleIntent(context))
            Permissions.Grant.SpeechPack, Permissions.Grant.TtsVoice, Permissions.Grant.Shizuku -> guide = g
            Permissions.Grant.None -> Unit
        }
    }

    val ready = items.count { it.granted }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text(L.t("$ready of ${items.size} ready", "$ready מתוך ${items.size} מוכנים"), style = MaterialTheme.typography.titleLarge,
                color = if (ready == items.size) Ok else Warn, modifier = Modifier.padding(top = 12.dp))
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
                if ((!p.granted || p.key == "shizuku") && p.grant != Permissions.Grant.None) {
                    Button(onClick = { grant(p) }, modifier = Modifier.heightIn(min = 56.dp)) {
                        Text(if (p.grant is Permissions.Grant.Runtime || p.grant is Permissions.Grant.Screen || p.grant == Permissions.Grant.DialerRole) L.t("Grant", "אישור") else L.t("How to set up", "איך מגדירים"))
                    }
                }
            }
        }
        item {
            InfoCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(L.t("Use internet speech recognition when needed", "זיהוי דיבור עם אינטרנט כשצריך"), style = MaterialTheme.typography.titleMedium)
                        Text(L.t(
                            "On: works offline when the language pack is installed, and uses Google online otherwise. Off: offline only.",
                            "פועל: בלי אינטרנט כשחבילת השפה מותקנת, ועם Google באינטרנט כשלא. כבוי: רק בלי אינטרנט.",
                        ), style = MaterialTheme.typography.bodyMedium)
                    }
                    Switch(checked = settings.allowOnlineSpeech, onCheckedChange = { app.settings.setAllowOnlineSpeech(it) })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(L.t("Speak inside calls when possible", "לדבר בתוך השיחה כשאפשר"), style = MaterialTheme.typography.titleMedium)
                        Text(L.t("Uses Shizuku when connected; otherwise speakerphone.", "משתמש ב-Shizuku כשהוא מחובר; אחרת ברמקול."), style = MaterialTheme.typography.bodyMedium)
                    }
                    Switch(checked = settings.useCallBridge, onCheckedChange = {
                        app.settings.setUseCallBridge(it)
                        if (it) CallBridge.refresh() else CallBridge.disconnect()
                    })
                }
            }
        }
        item {
            InfoCard {
                Text(L.t("What Android will not allow", "מה אנדרואיד לא מאפשר"), style = MaterialTheme.typography.titleMedium)
                Text(L.t(
                    "• Ordinary apps cannot play audio into a call. With Shizuku (wireless debugging) AutoAgent can, on phones whose audio hardware exposes an in-call output; otherwise it uses speakerphone.",
                    "• אפליקציה רגילה לא יכולה להשמיע קול לתוך שיחה. עם Shizuku (ניפוי באגים אלחוטי) AutoAgent יכול, בטלפונים שהחומרה שלהם מאפשרת; אחרת הוא משתמש ברמקול.",
                ))
                Text(L.t(
                    "• A PIN/fingerprint lock is never bypassed. For WhatsApp sends AutoAgent turns the screen on and shows Android's unlock prompt; the message goes out right after you unlock.",
                    "• נעילה עם קוד או טביעת אצבע אף פעם לא נעקפת. בשליחה בוואטסאפ AutoAgent מדליק את המסך ומציג את מסך הנעילה של אנדרואיד; ההודעה יוצאת מיד אחרי שאתה פותח.",
                ))
                Text(L.t(
                    "• Sideloaded apps on Android 13+ must allow \"restricted settings\" before Accessibility and Notification access can be turned on.",
                    "• באפליקציה שהותקנה מקובץ, באנדרואיד 13 ומעלה צריך לאשר \"הגדרות מוגבלות\" לפני שאפשר להפעיל נגישות וגישה להתראות.",
                ))
                Text(L.t(
                    "• Reading text from receipt photos (ML Kit) supports Latin letters only. Dictate Hebrew receipts instead.",
                    "• קריאת טקסט מתמונת קבלה תומכת רק באותיות לטיניות. קבלות בעברית עדיף להכתיב בקול.",
                ))
            }
        }
        item { Text(" ") }
    }

    guide?.let { g ->
        GuideDialog(
            grant = g,
            bridge = bridge,
            onDismiss = { guide = null; tick++ },
            open = ::open,
            setNote = { note = it },
        )
    }
}

@Composable
private fun GuideDialog(
    grant: Permissions.Grant,
    bridge: CallBridge.State,
    onDismiss: () -> Unit,
    open: (Intent) -> Boolean,
    setNote: (String) -> Unit,
) {
    val context = LocalContext.current
    var msg by remember { mutableStateOf<String?>(null) }
    fun launch(vararg intents: Intent): Boolean {
        for (i in intents) if (open(i)) return true
        msg = L.t("This phone has no such screen; follow the steps manually.", "בטלפון הזה אין מסך כזה; צריך לעשות את השלבים ידנית.")
        return false
    }
    val (title, steps) = when (grant) {
        Permissions.Grant.SpeechPack -> L.t("Offline speech recognition", "זיהוי דיבור בלי אינטרנט") to listOf(
            L.t("Tap \"Download automatically\" below and wait a minute.", "לוחצים \"הורדה אוטומטית\" למטה ומחכים דקה."),
            L.t("If it stays red: open the Google app → your profile picture → Settings → Voice → Offline speech recognition → download Hebrew.",
                "אם זה נשאר אדום: פותחים את אפליקציית Google ← תמונת הפרופיל ← הגדרות ← קול ← זיהוי דיבור אופליין ← מורידים עברית."),
            L.t("On Samsung you can also search Settings for \"On-device speech recognition\" and add Hebrew.",
                "בסמסונג אפשר גם לחפש בהגדרות \"זיהוי דיבור במכשיר\" ולהוסיף עברית."),
            L.t("Until then, with \"internet speech recognition\" on, everything works online.",
                "עד אז, כשהמתג \"זיהוי דיבור עם אינטרנט\" דלוק, הכול עובד עם אינטרנט."),
        )
        Permissions.Grant.TtsVoice -> L.t("Hebrew speaking voice", "קול דיבור בעברית") to listOf(
            L.t("Install \"Speech Services by Google\" from Google Play if it is missing.", "אם חסר, מתקינים את \"שירותי דיבור של Google\" מ-Google Play."),
            L.t("Settings → Accessibility → Text-to-speech output → Preferred engine: Google.", "הגדרות ← נגישות ← פלט טקסט לדיבור ← מנוע מועדף: Google."),
            L.t("Tap the gear next to Google → Install voice data → Hebrew.", "לוחצים על גלגל השיניים ליד Google ← התקנת נתוני קול ← עברית."),
        )
        else -> L.t("Talk inside calls (Shizuku)", "דיבור בתוך השיחה (Shizuku)") to listOf(
            L.t("1. Install Shizuku from Google Play (free, open source).", "1. מתקינים את Shizuku מ-Google Play (חינמי, קוד פתוח)."),
            L.t("2. Settings → About phone → Software information → tap \"Build number\" 7 times.", "2. הגדרות ← אודות הטלפון ← פרטי תוכנה ← לוחצים 7 פעמים על \"מספר Build\"."),
            L.t("3. Settings → Developer options → turn on \"Wireless debugging\" (needs Wi-Fi).", "3. הגדרות ← אפשרויות מפתחים ← מפעילים \"ניפוי באגים אלחוטי\" (צריך Wi-Fi)."),
            L.t("4. Open Shizuku → \"Start via Wireless debugging\" → Pairing, and follow its notification to enter the pairing code.",
                "4. פותחים את Shizuku ← \"הפעלה דרך ניפוי באגים אלחוטי\" ← צימוד, ומזינים את קוד הצימוד דרך ההתראה של Shizuku."),
            L.t("5. Back here, tap \"Allow AutoAgent in Shizuku\".", "5. חוזרים לכאן ולוחצים \"לאשר את AutoAgent ב-Shizuku\"."),
            L.t("After every phone restart: open Shizuku and tap Start again (Wi-Fi on).", "אחרי כל הפעלה מחדש של הטלפון: פותחים את Shizuku ולוחצים שוב הפעלה (עם Wi-Fi)."),
            L.t("If Samsung \"Auto Blocker\" is on (Settings → Security and privacy) it may block this.", "אם \"חוסם אוטומטי\" של סמסונג דלוק (הגדרות ← אבטחה ופרטיות), הוא עלול לחסום את זה."),
            L.t("Status: ", "מצב: ") + CallBridge.statusText() + if (bridge.probe.isNotBlank()) " [${bridge.probe}]" else "",
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                steps.forEach { Text(it, style = MaterialTheme.typography.bodyLarge) }
                msg?.let { Text(it, color = Warn) }
                val big = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                when (grant) {
                    Permissions.Grant.SpeechPack -> {
                        Button(onClick = {
                            msg = if (SpeechSupport.download(context)) L.t("Download requested. Come back in a minute.", "ביקשתי הורדה. כדאי לחזור בעוד דקה.")
                            else L.t("Automatic download is not available on this phone; use the steps above.", "הורדה אוטומטית לא זמינה בטלפון הזה; צריך לעשות את השלבים למעלה.")
                        }, modifier = big) { Text(L.t("Download automatically", "הורדה אוטומטית")) }
                        OutlinedButton(onClick = {
                            context.packageManager.getLaunchIntentForPackage("com.google.android.googlequicksearchbox")?.let { launch(it) }
                                ?: run { msg = L.t("Google app not found.", "אפליקציית Google לא נמצאה.") }
                        }, modifier = big) { Text(L.t("Open the Google app", "פתיחת אפליקציית Google")) }
                        OutlinedButton(onClick = { launch(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS), Intent(Settings.ACTION_LOCALE_SETTINGS)) }, modifier = big) {
                            Text(L.t("Open language & voice settings", "פתיחת הגדרות שפה וקול"))
                        }
                    }
                    Permissions.Grant.TtsVoice -> {
                        Button(onClick = { launch(Intent("com.android.settings.TTS_SETTINGS"), Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }, modifier = big) {
                            Text(L.t("Open text-to-speech settings", "פתיחת הגדרות טקסט לדיבור"))
                        }
                        OutlinedButton(onClick = { launch(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).setPackage("com.google.android.tts"), Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)) }, modifier = big) {
                            Text(L.t("Install voice data", "התקנת נתוני קול"))
                        }
                        OutlinedButton(onClick = { launch(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.google.android.tts"))) }, modifier = big) {
                            Text(L.t("Get Google speech services", "הורדת שירותי הדיבור של Google"))
                        }
                    }
                    else -> {
                        if (!bridge.shizukuInstalled) {
                            Button(onClick = { launch(CallBridge.installIntent()) }, modifier = big) { Text(L.t("Install Shizuku", "התקנת Shizuku")) }
                        } else {
                            CallBridge.openShizukuIntent(context)?.let { i ->
                                OutlinedButton(onClick = { launch(i) }, modifier = big) { Text(L.t("Open Shizuku", "פתיחת Shizuku")) }
                            }
                        }
                        OutlinedButton(onClick = { launch(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS), Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)) }, modifier = big) {
                            Text(L.t("Open developer options", "פתיחת אפשרויות מפתחים"))
                        }
                        Button(
                            onClick = {
                                if (!bridge.shizukuRunning) msg = L.t("Start Shizuku first (step 4).", "קודם צריך להפעיל את Shizuku (שלב 4).")
                                else { CallBridge.requestPermission(); setNote(L.t("Approve AutoAgent in the Shizuku prompt.", "צריך לאשר את AutoAgent בחלון של Shizuku.")) }
                            },
                            enabled = bridge.shizukuInstalled && !bridge.connected,
                            modifier = big,
                        ) { Text(L.t("Allow AutoAgent in Shizuku", "לאשר את AutoAgent ב-Shizuku")) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(L.t("Close", "סגירה")) } },
    )
}
