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
import android.speech.SpeechRecognizer
import android.telecom.TelecomManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.autoagent.app.access.AutomationService
import com.autoagent.app.alarm.AlarmScheduler
import com.autoagent.app.calls.PhoneAccounts

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

    fun items(c: Context): List<Item> {
        val pkgUri = Uri.parse("package:${c.packageName}")
        val list = mutableListOf<Item>()
        list += Item(
            "dialer", "Default phone app (dialer role)",
            "Lets AutoAgent show calls and answer, reject, hold and hang up.",
            PhoneAccounts.isDefaultDialer(c), Grant.DialerRole,
        )
        list += Item(
            "phone", "Phone", "Place calls and answer them.",
            phonePermissions().all { has(c, it) }, Grant.Runtime(phonePermissions()),
        )
        list += Item(
            "contacts", "Contacts", "Auto-answer only callers NOT in contacts; voice \"call <name>\".",
            has(c, Manifest.permission.READ_CONTACTS), Grant.Runtime(listOf(Manifest.permission.READ_CONTACTS)),
        )
        list += Item(
            "sms", "SMS", "Send your scheduled SMS messages.",
            has(c, Manifest.permission.SEND_SMS), Grant.Runtime(listOf(Manifest.permission.SEND_SMS)),
        )
        list += Item(
            "mic", "Microphone", "Voice commands, dictation, and hearing callers.",
            has(c, Manifest.permission.RECORD_AUDIO), Grant.Runtime(listOf(Manifest.permission.RECORD_AUDIO)),
        )
        list += Item(
            "post", "Show notifications", "Report every automated action and show alarms.",
            Build.VERSION.SDK_INT < 33 || has(c, Manifest.permission.POST_NOTIFICATIONS),
            if (Build.VERSION.SDK_INT >= 33) Grant.Runtime(listOf(Manifest.permission.POST_NOTIFICATIONS)) else Grant.None,
        )
        list += Item(
            "listener", "Notification access", "Store incoming SMS/WhatsApp/app notification text locally for summaries.",
            NotificationManagerCompat.getEnabledListenerPackages(c).contains(c.packageName),
            Grant.Screen(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)),
        )
        list += Item(
            "exact", "Alarms & reminders (exact alarms)", "Fire alarms and scheduled sends on the minute.",
            AlarmScheduler.canScheduleExact(c),
            if (Build.VERSION.SDK_INT >= 31) Grant.Screen(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkgUri)) else Grant.None,
        )
        if (Build.VERSION.SDK_INT >= 34) {
            list += Item(
                "fullscreen", "Full-screen alerts", "Show the alarm and incoming-call screen over the lock screen.",
                c.getSystemService(NotificationManager::class.java).canUseFullScreenIntent(),
                Grant.Screen(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkgUri)),
            )
        }
        list += Item(
            "accessibility", "Accessibility service",
            "Only for scheduled sends in WhatsApp/other apps: opens the app, pastes your saved text, taps Send. " +
                "If Android greys it out: App info → ⋮ → Allow restricted settings.",
            AutomationService.isEnabled(c), Grant.Screen(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)),
        )
        list += Item(
            "battery", "Battery unrestricted", "Keeps schedules and the listener alive in Doze.",
            c.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(c.packageName),
            Grant.Screen(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkgUri)),
        )
        list += Item(
            "speech", "Offline speech recognition",
            "On-device recognizer so voice commands work with no network. Tap to download the language pack.",
            Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(c),
            Grant.SpeechPack,
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
