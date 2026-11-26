package com.autoagent.app.ui

import android.Manifest
import android.app.NotificationManager
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.telecom.TelecomManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.autoagent.app.access.AutomationService
import com.autoagent.app.alarm.AlarmScheduler
import com.autoagent.app.calls.PhoneAccounts
import com.autoagent.app.util.L
import com.autoagent.app.voice.SpeechSupport

/** Everything AutoAgent needs, with live status and how to grant it. */
object Permissions {
    sealed interface Grant {
        data class Runtime(val permissions: List<String>) : Grant
        data class Screen(val intent: Intent) : Grant
        data object DialerRole : Grant
        data object SpeechPack : Grant
        data object None : Grant
    }

    data class Item(val key: String, val title: String, val why: String, val granted: Boolean, val grant: Grant)

    private fun has(c: Context, p: String) = ContextCompat.checkSelfPermission(c, p) == PackageManager.PERMISSION_GRANTED

    fun phonePermissions(): List<String> = listOf(
        Manifest.permission.CALL_PHONE,
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.ANSWER_PHONE_CALLS,
    )

    private val langName get() = L.t("English", "עברית")

    fun items(c: Context): List<Item> {
        val pkgUri = Uri.parse("package:${c.packageName}")
        val list = mutableListOf<Item>()
        list += Item(
            "dialer", L.t("Default phone app (dialer role)", "אפליקציית טלפון ראשית (תפקיד חייגן)"),
            L.t("Lets AutoAgent show calls and answer, reject, hold and hang up.", "מאפשר ל-AutoAgent להציג שיחות, לענות, לדחות, להעביר להמתנה ולנתק."),
            PhoneAccounts.isDefaultDialer(c), Grant.DialerRole,
        )
        list += Item(
            "phone", L.t("Phone", "טלפון"), L.t("Place calls and answer them.", "חיוג ומענה לשיחות."),
            phonePermissions().all { has(c, it) }, Grant.Runtime(phonePermissions()),
        )
        list += Item(
            "contacts", L.t("Contacts", "אנשי קשר"),
            L.t("Auto-answer only callers NOT in contacts; voice \"call <name>\".", "מענה אוטומטי רק למי שלא באנשי הקשר; \"תתקשר ל<שם>\" בקול."),
            has(c, Manifest.permission.READ_CONTACTS), Grant.Runtime(listOf(Manifest.permission.READ_CONTACTS)),
        )
        list += Item(
            "sms", "SMS", L.t("Send your scheduled SMS messages.", "שליחת הודעות ה-SMS שתזמנת."),
            has(c, Manifest.permission.SEND_SMS), Grant.Runtime(listOf(Manifest.permission.SEND_SMS)),
        )
        list += Item(
            "mic", L.t("Microphone", "מיקרופון"), L.t("Voice commands, dictation, and hearing callers.", "פקודות קוליות, הכתבה ושמיעת מתקשרים."),
            has(c, Manifest.permission.RECORD_AUDIO), Grant.Runtime(listOf(Manifest.permission.RECORD_AUDIO)),
        )
        list += Item(
            "post", L.t("Show notifications", "הצגת התראות"), L.t("Report every automated action and show alarms.", "דיווח על כל פעולה אוטומטית והצגת שעונים מעוררים."),
            Build.VERSION.SDK_INT < 33 || has(c, Manifest.permission.POST_NOTIFICATIONS),
            if (Build.VERSION.SDK_INT >= 33) Grant.Runtime(listOf(Manifest.permission.POST_NOTIFICATIONS)) else Grant.None,
        )
        list += Item(
            "listener", L.t("Notification access", "גישה להתראות"),
            L.t("Store incoming SMS/WhatsApp/app notification text locally for summaries.", "שמירת טקסט מהתראות נכנסות (SMS, וואטסאפ ואפליקציות) בטלפון בלבד, לצורך סיכומים."),
            NotificationManagerCompat.getEnabledListenerPackages(c).contains(c.packageName),
            Grant.Screen(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)),
        )
        list += Item(
            "exact", L.t("Alarms & reminders (exact alarms)", "שעונים ותזכורות (שעונים מדויקים)"),
            L.t("Fire alarms and scheduled sends on the minute.", "הפעלת שעונים ושליחות מתוזמנות בדיוק בזמן."),
            AlarmScheduler.canScheduleExact(c),
            if (Build.VERSION.SDK_INT >= 31) Grant.Screen(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkgUri)) else Grant.None,
        )
        if (Build.VERSION.SDK_INT >= 34) {
            list += Item(
                "fullscreen", L.t("Full-screen alerts", "התראות במסך מלא"),
                L.t("Show the alarm and incoming-call screen over the lock screen.", "הצגת מסך השעון המעורר והשיחה הנכנסת מעל מסך הנעילה."),
                c.getSystemService(NotificationManager::class.java).canUseFullScreenIntent(),
                Grant.Screen(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkgUri)),
            )
        }
        list += Item(
            "accessibility", L.t("Accessibility service", "שירות נגישות"),
            L.t(
                "Only for scheduled sends in WhatsApp/other apps: opens the app, pastes your saved text, taps Send. " +
                    "If Android greys it out: App info → ⋮ → Allow restricted settings.",
                "רק לשליחות מתוזמנות בוואטסאפ ובאפליקציות אחרות: פותח את האפליקציה, מדביק את הטקסט ששמרת ולוחץ שלח. " +
                    "אם האפשרות אפורה: פרטי האפליקציה ← ⋮ ← לאשר הגדרות מוגבלות.",
            ),
            AutomationService.isEnabled(c), Grant.Screen(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)),
        )
        list += Item(
            "battery", L.t("Battery unrestricted", "סוללה ללא הגבלה"),
            L.t("Keeps schedules and the listener alive in Doze.", "שומר על התזמונים והאזנה להתראות פעילים גם במצב שינה."),
            c.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(c.packageName),
            Grant.Screen(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkgUri)),
        )
        list += Item(
            "speech", L.t("Offline speech recognition ($langName)", "זיהוי דיבור בלי אינטרנט ($langName)"),
            L.t(
                "On-device language pack so voice commands and callers are understood with no network. Tap to download.",
                "חבילת שפה במכשיר, כדי שהפקודות והמתקשרים יובנו גם בלי אינטרנט. הקש להורדה.",
            ),
            SpeechSupport.offlinePack.value == true,
            Grant.SpeechPack,
        )
        list += Item(
            "tts", L.t("Speaking voice ($langName)", "קול דיבור ($langName)"),
            L.t(
                "Text-to-speech voice so AutoAgent and the call assistant speak your language. Tap to install voice data.",
                "קול הקראה כדי ש-AutoAgent והעוזר בשיחות ידברו בעברית. הקש להתקנת נתוני קול (למשל במנוע של Google).",
            ),
            SpeechSupport.ttsVoice.value == true,
            Grant.Screen(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)),
        )
        return list
    }

    fun dialerRoleIntent(c: Context): Intent =
        if (Build.VERSION.SDK_INT >= 29) {
            c.getSystemService(RoleManager::class.java).createRequestRoleIntent(RoleManager.ROLE_DIALER)
        } else {
            Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER)
                .putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, c.packageName)
        }
}
