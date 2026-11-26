# AutoAgent

A personal Android assistant for **your own phone**. It controls the phone through real Android APIs
(Telecom, SmsManager, AlarmManager, AccessibilityService, NotificationListenerService, SpeechRecognizer,
TextToSpeech, ML Kit). Everything is stored on the device (Room + app files). There is no account and no cloud model.
Every automated action is shown in a notification and logged — nothing runs hidden.

- Kotlin, Jetpack Compose (Material 3, dark), MVVM (AndroidViewModel + StateFlow), Room, WorkManager
- minSdk 26, targetSdk/compileSdk 37 (compiled against 37.1), single `:app` module
- Gradle version catalog: `gradle/libs.versions.toml` (AGP 9.5, Kotlin 2.4, KSP for Room)

> This repo also still contains `kablon-upload.zip` and its GitHub Pages workflow (`.github/workflows/deploy.yml`).
> The Android app does not use them.

## Build

### Android Studio
1. Install the latest stable Android Studio (it must support AGP 9.5).
2. **File → Open** and select this folder. Let Gradle sync.
3. When asked, install **Android SDK Platform 37.1** and **Build-Tools 37.0.0** (SDK Manager → SDK Platforms, show package details).
4. Studio uses its bundled JDK 21 — fine (JDK 17+ required).
5. Plug in your phone with USB debugging on, pick the `app` configuration, press **Run**.

### Command line
```bash
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
CI (`.github/workflows/android.yml`) runs `assembleDebug` on every push and uploads the APK as an artifact.

## First run: grant everything
Open the shield icon (top right) → **Permissions**. Each row is green or red and has a **Grant** button.

| Item | Why |
| --- | --- |
| Default phone app (dialer role) | Required for `InCallService`: show, answer, reject, hold, hang up, auto-answer |
| Phone (CALL_PHONE, READ_PHONE_STATE, ANSWER_PHONE_CALLS) | Place and control calls |
| Contacts | Auto-answer only numbers **not** in contacts; voice "call &lt;name&gt;" |
| SMS | Scheduled SMS through `SmsManager` |
| Microphone | Voice commands, dictation, hearing callers |
| Show notifications | Reports for every automated action, alarms |
| Notification access | Stores incoming notification text locally for summaries |
| Alarms & reminders | Exact alarms / on-time schedules |
| Full-screen alerts (Android 14+) | Alarm and incoming-call screen over the lock screen |
| Accessibility service | Only for scheduled sends in WhatsApp/other apps |
| Battery unrestricted | Keeps schedules alive in Doze |
| Offline speech recognition | On-device language pack so voice works without network |

**Sideloaded APK on Android 13+:** Accessibility and Notification access are greyed out until you open
*Settings → Apps → AutoAgent → ⋮ → Allow restricted settings*.

## Features

### 1. Phone (default dialer)
`AgentInCallService` (InCallService) receives every call once AutoAgent is the default phone app. `InCallActivity`
offers Answer / Reject / Hold / Resume / Speaker / Mute / Hang up. Calls are placed with `TelecomManager.placeCall`.
`TestConnectionService` is a **self-managed ConnectionService** ("AutoAgent test line") used by
*Auto-answer → Run a test call* to rehearse the assistant script end-to-end without a real caller.

### 2. Auto-answer (unknown numbers only, opt-in)
Off by default. When on, an incoming call is auto-answered only if the number is **not in contacts**
(hidden numbers are never answered; if Contacts permission is off nothing is auto-answered). It rings for a
configurable delay first so you can pick up.
Script: first utterance is always **"This call is answered by an assistant and is recorded."**, then
"What do you need?", replies from local keyword rules, "The owner will call you back. Goodbye."
Saved: number, transcript, matched intents, and the audio file under `files/calls/` **if the OS allowed capture**;
otherwise the record states why only the transcript was saved.

### 3. Alarms
`AlarmManager.setAlarmClock` (exact, Doze-safe), full-screen ringing screen with Snooze/Dismiss, re-armed by
`BootReceiver` on `BOOT_COMPLETED`, app update and clock/timezone changes, plus a periodic WorkManager watchdog.

### 4. Scheduled sends
Pick SMS, WhatsApp, or any installed app that accepts shared text; one contact/group; text; time.
- **SMS:** `SmsManager.sendMultipartTextMessage`, result logged from the radio callback.
- **Other apps:** at the due time an expedited WorkManager job asks the accessibility service to open the app with
  the saved text (WhatsApp: `api.whatsapp.com/send?phone=` deep link; others: `ACTION_SEND`), select the
  recipient only if exactly one on-screen item matches the saved name, verify the text box contains exactly the
  saved text, tap Send once, and verify. Anything unexpected (locked phone, other text, two matches, different
  app in front, timeout) → **abort + notification**. It never retries a tap or guesses.
- Guards: one recipient per schedule (no lists), max 25 schedules per 24 h, max 1000 characters, sends more than 15 min late are not sent.

### 5. Inbox summaries
`InboxListenerService` stores SMS/WhatsApp/other notification text in Room (MessagingStyle aware, de-duplicated,
pruned after 30 days). **Summarize** produces an offline rule-based summary per sender: time, questions/asks,
money amounts (₪ $ € £, ILS/NIS/USD…). **Read aloud** uses TextToSpeech.

### 6. Receipts
Share an image or audio file **to AutoAgent** (or use the buttons in *Receipts*). Images: bundled ML Kit text
recognition (on-device). Voice notes: decoded with MediaCodec and fed to the on-device SpeechRecognizer
(`EXTRA_AUDIO_SOURCE`, Android 13+); "Dictate receipt" works on all versions. A rule parser extracts merchant,
date and total; you can edit every field. **Optional IMAP**: only if you enter host, user and an app password
(stored encrypted with an Android Keystore key); otherwise e-mail is skipped entirely. INTERNET is used only for this.

### 7. Voice
Tap the mic → `SpeechRecognizer` (on-device recognizer when available, `EXTRA_PREFER_OFFLINE` always) →
command → reply with TextToSpeech. Offline commands:
- "set alarm for 7:30", "wake me up in 20 minutes"
- "call 050 123 4567", "call Dana"
- "schedule SMS to Dana at 6 pm saying I'm on my way"
- "read my summaries"
- "turn auto-answer on/off"

### 8. Call brain
`CallBrain` + editable `keyword_rules` table (who is this, callback time, take a message, urgent, sales; add your own).
Unknown → "I will pass this to the owner." No network, no model.

### Foreground service
`AgentForegroundService` shows a persistent notification whenever something is pending (schedules, alarms,
auto-answer armed) or the microphone is listening, and says exactly which.

## What Android will not allow (and what AutoAgent does instead)
- **Injecting audio into a phone call / recording call audio.** The `VOICE_CALL` source and call uplink need
  `CAPTURE_AUDIO_OUTPUT`, a system/privileged permission. AutoAgent tries `VOICE_CALL`; when refused (normal case)
  it uses speakerphone so the caller hears TTS through the mic and the recognizer hears the caller through the
  speaker. The UI says so, and the saved record explains that only the transcript was kept.
- **Speech recognition during calls** may be blocked by some OEMs (mic busy) — the record shows the recognizer error.
- **Sending in WhatsApp/other apps without the UI.** There is no public API; the only legitimate route is the
  accessibility service acting on the visible, unlocked screen. Locked phone → aborted.
- **Background starts.** Android may refuse to start a foreground service from some background states; alarms,
  boot and the accessibility service have exemptions, otherwise the status notification appears next time you open the app.
- **Shared audio transcription before Android 13** (`EXTRA_AUDIO_SOURCE` doesn't exist) — use dictation.
- **Exact alarms** need "Alarms & reminders" (Android 12+); without it schedules are inexact and the UI warns you.
- **Play Store**: this app uses dialer, accessibility, SMS and exact-alarm capabilities in ways Play policy restricts.
  It is meant to be sideloaded onto your own phone.

## Code map
```
app/src/main/java/com/autoagent/app/
  AutoAgentApp.kt          app singleton: Room, settings, channels, seeding
  MainActivity.kt          Compose host, DIAL intents, share hand-off
  data/                    Room entities/DAOs/DB, Settings, SecretBox (Keystore)
  calls/                   InCallService, CallManager, InCallActivity, AssistantSession, CallBrain,
                           CallRecorder, TestConnectionService (self-managed), PhoneAccounts
  alarm/                   AlarmScheduler, AlarmReceiver, AlarmRingService, AlarmActivity, BootReceiver
  schedule/                SendScheduler, SendReceiver, SendWorker, SmsSender, WatchdogWorker
  access/                  AutomationService (AccessibilityService)
  inbox/                   InboxListenerService, Summarizer
  receipts/                ReceiptParser, OcrEngine, AudioTranscriber, ReceiptIngest, ImapFetcher/ImapWorker, ShareReceiverActivity
  voice/                   SpeechListener, Speaker (TTS), VoiceCommands
  service/                 AgentForegroundService, AgentStatus
  ui/                      AppRoot (nav), ViewModels, Permissions, screens/
```
