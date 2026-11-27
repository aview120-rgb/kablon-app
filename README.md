<div dir="rtl">

# AutoAgent — אוטומציה אישית לאנדרואיד, בעברית מלאה

אפליקציית אנדרואיד **בעברית מלאה (RTL בכל המסכים, הכפתורים, ההתראות והיומן)** שמבצעת אוטומציה אישית של ממשק
המשתמש וההתראות באמצעות **שירות נגישות (AccessibilityService) — ללא Root**, ומסתנכרנת מול **שרת תור משימות**
שממשיך לעבד ולתזמן משימות גם כשהטלפון כבוי או לא מקוון, ומוסר את כל התוצאות המוכנות ברגע שהטלפון חוזר לרשת.

## מה יש בפרויקט

| חלק | תיקייה | תפקיד |
| --- | --- | --- |
| אפליקציית אנדרואיד | `app/` | Kotlin, Jetpack Compose, Room, WorkManager. ממשק עברי RTL כברירת מחדל |
| שרת תור משימות | `server/` | Node.js ללא תלויות. תור אסינכרוני, עיבוד בזמן ניתוק, סנכרון, ממשק ניהול בעברית |

### חיווי מצב בכל מסך
מתחת לכותרת של כל מסך יש פס מצב עם ארבעה חיוויים (ירוק / צהוב / אדום):
**סוללה** (אחוז, טעינה, חיסכון) · **רשת** (Wi‑Fi / סלולרי / לא מחובר / ללא אינטרנט) ·
**שירות נגישות** (פעיל / לא מגיב / כבוי) · **שרת** (מחובר / מחובר-חי / לא מקוון-בתור / שגיאה / אסימון שגוי).
הקשה על חיווי הנגישות או השרת פותחת את מסך **סנכרון ושרת**.

### שירות נגישות מודולרי ויציב (ללא Root)
* `access/AutomationService` — מבצע את פעולות המסך בלבד (פתיחת צ'אט, הדבקת הטקסט השמור, אימות, לחיצה אחת על שלח, אימות נוסף). אם משהו במסך לא תואם — עוצר ומודיע, אף פעם לא מנחש.
* `access/SerialActionQueue` + `ScreenActions` — **תור פעולות מסך**: פעולה אחת בכל רגע (אין התנגשויות), עדיפויות, זמן קצוב לכל פעולה, מרווח בין פעולות, והשהיה אוטומטית בזמן שיחת טלפון.
* `access/AccessibilityHealth` — **בדיקת סטטוס והתאוששות**: מבחין בין "כבוי", "מופעל אבל לא מגיב" ו"פעיל". כשהשירות לא מגיב: מפעיל אותו מחדש דרך Shizuku (אם מותקן — עדיין ללא Root), ואחרת שולח התראה עם קיצור ישיר להגדרה. הודעות שהגיע זמנן כשהשירות לא היה זמין לא נכשלות — הן ממתינות (`ממתין לשירות הנגישות`) ונשלחות ברגע שהשירות חוזר (עד 60 דקות). בדיקה תקופתית כל 30 דקות (Watchdog).
* `access/MessengerProfiles` — פרופילים לאפליקציות מסרים: WhatsApp, WhatsApp Business, Telegram, Signal, Google Messages, Messenger, Viber (קישורי צ'אט ישירים, מזהי כפתור שלח, מספרים ישראליים 05x → 9725x).
* **הכנה או שליחה:** כל הודעה יכולה להישלח אוטומטית או רק **להיות מוכנה** (הצ'אט נפתח עם הטקסט, ואתם לוחצים שלח). הודעות מהשרת **מוכנות בלבד** כברירת מחדל, אלא אם הפעלתם "השרת רשאי ללחוץ שלח".

### סנכרון מול השרת — גם בלי רשת
* `sync/SyncEngine` — סנכרון דו-כיווני בבקשה אחת: מעלה את כל מה שנאסף בזמן ניתוק (תוצאות ביצוע, טקסט התראות אם הופעל, משימות שנוצרו בטלפון) ומוריד את כל מה שהשרת הכין. כל פריט מאושר לפי מזהה — ניתוק באמצע לא יוצר כפילויות.
* `sync/Outbox` — תור יוצא מקומי (Room) ששומר נתונים כל עוד אין רשת.
* `status/NetworkMonitor` — ברגע שהרשת חוזרת מופעל סנכרון מיידי.
* `sync/SyncScheduler` — משימת WorkManager עם תנאי "רשת מחוברת": אנדרואיד מריץ אותה ברגע שיש רשת, **גם אם האפליקציה סגורה או שהטלפון אותחל**, עם ניסיונות חוזרים אקספוננציאליים. בנוסף סנכרון תקופתי כל 15 דקות.
* `sync/LiveSync` — חיבור חי (long-poll) כשהאפליקציה רצה: משימות מגיעות תוך שניות, **התחברות מחדש אוטומטית** עם השהיה אקספוננציאלית (2ש׳ … 5 דק׳), השהיה כשאין רשת.
* `sync/RemoteTaskExecutor` — משימה שהתקבלה נשמרת ואז מבוצעת מיד, מתוזמנת מקומית (כדי לרוץ בזמן גם אם הרשת תיפול שוב) או מסומנת "פג תוקף" אם הגיעה מאוחר מדי. תיקון אוטומטי להפרש שעונים בין הטלפון לשרת.
* **טיפול בשגיאות רשת:** שגיאות מסווגות — רשת/DNS/זמן קצוב/TLS ושגיאות שרת 5xx מקבלות ניסיון חוזר; אסימון שגוי (401) עוצר ומציג "אסימון שגוי" בלי לולאת ניסיונות. כל השגיאות מוצגות בעברית במסך וביומן.

## התקנה והפעלה

### 1. הפעלת השרת (מחשב ביתי, שרת או VPS)
```bash
cd server
export KABLON_TOKEN="בחרו-אסימון-ארוך-וסודי"
npm start
# ממשק ניהול: http://<כתובת-המחשב>:8787
```
או עם Docker — ראו [`server/README.md`](server/README.md) (כולל כל ה-API ודוגמאות `curl`).

### 2. בניית האפליקציה
**Android Studio:** File → Open על התיקייה הזו → Run. (נדרשים Android SDK Platform 37.1 ו-Build-Tools 37.0.0; Studio מציע להתקין.)

**שורת פקודה:**
```bash
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew assembleDebug testDebugUnitTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
בכל דחיפה ל-`main` נבנה APK אוטומטית ב-GitHub Actions ומתפרסם בקישור הורדה קבוע (ראו למטה, "Direct download").

### 3. הרשאות בטלפון
מסך **הרשאות** (סמל המגן למעלה) מציג כל הרשאה בירוק/אדום עם כפתור "אשר". החשובות לאוטומציה:
* **שירות נגישות** — הגדרות ← נגישות ← AutoAgent ← הפעלה.
  ב-APK שהותקן ידנית באנדרואיד 13+: קודם הגדרות ← אפליקציות ← AutoAgent ← ⋮ ← **אפשר הגדרות מוגבלות**.
* **התראות**, **התראות מתקדמות (Notification access)** — לסיכומים ולהעלאה (אופציונלי) לשרת.
* **ללא הגבלת סוללה** — כדי שתזמונים ו-WorkManager ירוצו גם במצב Doze.
* (רשות) **Shizuku** — מאפשר התאוששות אוטומטית של שירות הנגישות ללא Root.

### 4. חיבור הטלפון לשרת
1. בסמל הענן למעלה ← **סנכרון ושרת**.
2. הפעילו **סנכרון עם השרת**, הזינו כתובת (למשל `http://192.168.1.10:8787`) ואת האסימון ← **שמירה** ← **בדיקת חיבור**.
3. החיווי "שרת" בפס המצב יהפוך לירוק ("מחובר (חי)"). המכשיר יופיע בממשק הניהול עם סוללה, רשת ומצב נגישות.
4. אופציות: **חיבור חי**, **העלאת טקסט התראות** (לסיכומים בשרת; כבוי כברירת מחדל), **השרת רשאי ללחוץ שלח** (כבוי כברירת מחדל), **בקש מהשרת סיכום הודעות יומי ב-08:00** (עובד גם בלי רשת — נשלח לשרת כשהחיבור חוזר).

### 5. בדיקה מקצה לקצה
1. בממשק הניהול צרו משימה "שליחת הודעה" לעוד 5 דקות לאיש קשר שלכם.
2. כבו את הרשת בטלפון (מצב טיסה). המשימה מעובדת בשרת ומחכה ("מוכן — ממתין לטלפון").
3. הדליקו את הרשת — תוך שניות היא מגיעה לטלפון ("נמסר לטלפון"), מתוזמנת, ובזמן שלה מבוצעת. התוצאה ("בוצע") חוזרת לשרת — גם אם הטלפון היה שוב לא מקוון בזמן הביצוע, היא תסונכרן בחיבור הבא.

## בדיקות
```bash
./gradlew testDebugUnitTest          # בדיקות יחידה: פרוטוקול סנכרון, Backoff, תור פעולות מסך, פרופילי מסרים, עברית
(cd server && npm test)              # בדיקות השרת: תור, עיבוד, ניסיונות חוזרים, משימות חוזרות, סנכרון, אחסון, HTTP

# בדיקת אינטגרציה: הלקוח של האפליקציה מול שרת אמיתי
(cd server && KABLON_TOKEN=t PORT=8799 DATA_FILE=/tmp/k.json node src/index.js) &
KABLON_TEST_SERVER=http://127.0.0.1:8799 KABLON_TEST_TOKEN=t ./gradlew testDebugUnitTest --tests '*ServerIntegrationTest*'
```
GitHub Actions (`.github/workflows/android.yml`) מריץ את כל אלה בכל דחיפה.

## פתרון תקלות
| תסמין | פתרון |
| --- | --- |
| "שרת: אסימון שגוי" | האסימון בטלפון שונה מ-`KABLON_TOKEN`. הקלידו אותו מחדש ושמרו. בלי `KABLON_TOKEN` השרת מייצר אסימון חדש בכל הפעלה |
| "שרת: שגיאה — מנסה שוב" | השרת כבוי / חסום בחומת אש / כתובת שגויה. "בדיקת חיבור" מציגה את הסיבה. הנתונים נשמרים בתור ויישלחו אוטומטית |
| "נגישות: לא מגיב" | "בדיקה ושחזור" במסך הסנכרון, או כבו והדליקו את AutoAgent בהגדרות הנגישות |
| הודעה "ממתין לשירות הנגישות" | תישלח לבד כשהשירות יחזור (עד 60 דקות); אחרי זה מסומנת "נכשל" והתוצאה מדווחת לשרת |
| משימה "פג תוקף" | הטלפון היה מנותק יותר מ-`maxLateMinutes` אחרי זמן הביצוע; הגדילו את הערך במשימה |
| שרת ב-`http://` לא נגיש מחוץ לבית | השתמשו ב-HTTPS (reverse proxy / Cloudflare Tunnel) |

## פרטיות ובטיחות
* אין שליחה המונית: נמען אחד לכל הודעה, עד 25 הודעות מתוזמנות ביממה, עד 1000 תווים.
* האסימון נשמר מוצפן במפתח Android Keystore. טקסט התראות מועלה לשרת רק אם הפעלתם זאת.
* כל פעולה אוטומטית מוצגת בהתראה ונרשמת ביומן הפעולות (מסך סנכרון ושרת).

---

</div>

# English documentation

## AutoAgent (English)

A personal Android assistant for **your own phone**. It controls the phone through real Android APIs
(Telecom, SmsManager, AlarmManager, AccessibilityService, NotificationListenerService, SpeechRecognizer,
TextToSpeech, ML Kit). Everything is stored on the device (Room + app files). There is no account and no cloud model.
Every automated action is shown in a notification and logged — nothing runs hidden.

- Kotlin, Jetpack Compose (Material 3, dark), MVVM (AndroidViewModel + StateFlow), Room, WorkManager
- minSdk 26, targetSdk/compileSdk 37 (compiled against 37.1), single `:app` module
- Gradle version catalog: `gradle/libs.versions.toml` (AGP 9.5, Kotlin 2.4, KSP for Room)

> This repo also still contains `kablon-upload.zip` and its GitHub Pages workflow (`.github/workflows/deploy.yml`).
> The Android app does not use them.

## Hebrew / English (עברית ואנגלית)
The whole app is bilingual. The language button in the top bar cycles **Auto (follow phone) → עברית → English**.
Hebrew switches the layout to right-to-left and changes everything together: screens, notifications, spoken
replies, the speech recognizer (`he-IL`), the TTS voice, and the call assistant's script and keyword rules.

Hebrew voice commands are understood in many natural phrasings, offline (rule-based grammar, no model):
- "תעיר אותי בשבע וחצי", "תעיר אותי מחר בשש", "ברבע לשמונה", "בעשרים לתשע בבוקר", "בעוד רבע שעה", "בעוד שעתיים"
- "תזכיר לי בעוד חצי שעה להוציא את הכביסה"
- "תתקשר לדנה", "תחייג ל-050-1234567"
- "תשלח הודעה לדנה מחר בשמונה שאני מאחר", "תשלח לאמא בשש: אני בדרך", "…תגיד לה שהפגישה בוטלה"
- "תקריא סיכום", "מה פספסתי?", "תפעיל / תכבה מענה אוטומטי", "עזרה"

Wake-up phrases ("תעיר אותי", "השכמה") read an hour without morning/evening as morning; other phrases pick the next
upcoming time. These phrasings are covered by unit tests: `./gradlew testDebugUnitTest`.

For Hebrew to work offline, the **Permissions** screen has two extra rows: the Hebrew on-device speech pack and a
Hebrew TTS voice. Limits: it understands the command types above, not arbitrary sentences; ML Kit OCR reads only
Latin script, so Hebrew printed receipts should be dictated.

## Talking inside calls (Shizuku, no root)
Ordinary apps cannot play audio into a phone call. AutoAgent can do it through **Shizuku**: Shizuku starts a
helper as the *shell* user via Android's own **Wireless debugging** (no root, no computer). The shell user may
write to the call's uplink ("in-call music" output) and read the call audio, so the assistant speaks straight
into the call, hears the caller and records both sides. Setup is in *Permissions → Talk inside calls*:
install Shizuku from Google Play → enable Developer options and Wireless debugging → start Shizuku → allow
AutoAgent. After every reboot, start Shizuku again. Whether the caller hears it depends on the phone's audio
hardware (reported as `uplink=true/false` in the status); when it isn't available AutoAgent falls back to
speakerphone automatically. The same bridge lets **"tell me when someone answers"** hear the line directly while
you wait on hold.

## Locked screen
SMS, alarms, calls and auto-answer work while locked. For WhatsApp/app sends AutoAgent turns the screen on and asks
Android to dismiss the lock: a swipe lock opens by itself; a PIN/fingerprint lock shows Android's own prompt and the
message is sent right after you unlock (it waits up to 60 minutes). The lock is never bypassed.

## Direct download
Every push to `main` publishes the APK at
`https://github.com/aview120-rgb/kablon-app/releases/download/autoagent-latest/AutoAgent.apk`.
All builds are signed with the same key (`app/autoagent.keystore`), so new versions install over old ones.

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
