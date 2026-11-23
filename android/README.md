# Kablon אוטומציה – אפליקציית אנדרואיד

אפליקציית Kotlin (Android Studio / Gradle) למכשיר אנדרואיד ייעודי: אוטומציה גלויה על המסך, מרכז אפליקציות וכלים פנימי, עיבוד מדיה, והכנה והעלאה ל-TikTok.

## מבנה

```
android/
├─ app/src/main/java/com/kablon/automation/
│  ├─ core/        Prefs, WorkflowLog (יומן שלבים), StorageBudget (יעד 80GB), Notifier
│  ├─ automation/  KablonAccessibilityService, OverlayPanel (חלונית צפה), AutomationController
│  │               (השהה/המשך/עצור), Automation (לחיצה/הקלדה/גלילה/אישורים), Flows (AI + TikTok)
│  ├─ store/       ToolCatalog ("חנות" פנימית), TermuxBridge (RUN_COMMAND)
│  ├─ media/       MediaComposer (לוגו + כיתוב), VideoProcessor (Media3), MediaFiles, AiApiClient
│  └─ ui/          Main, Store, Media, TikTok, Log, Settings
├─ app/src/test/   בדיקות יחידה
└─ scripts/smoke-test.sh   בדיקת אמולטור ב-CI
```

## בנייה

- אוטומטית: כל push שנוגע ב-`android/` מריץ את `.github/workflows/android-apk.yml`:
  בנייה ← בדיקות יחידה ← lint ← בדיקה על אמולטור (API 34) ← פרסום Release.
- קישור קבוע לגרסה האחרונה: `https://github.com/<owner>/<repo>/releases/latest/download/kablon-automation.apk`
- מקומית: `cd android && ./gradlew assembleRelease` (דורש JDK 17+ ו-Android SDK 34).

## חתימה

ה-APK נחתם במפתח קבוע (`app/kablon-debug.keystore`, סיסמה `android`) כדי שכל גרסה חדשה תותקן כעדכון מעל הקודמת.
זה מפתח מסוג debug ואינו סודי. למפתח פרטי: הוסף ב-GitHub Secrets את
`KABLON_KEYSTORE_BASE64`, `KABLON_KEYSTORE_PASSWORD`, `KABLON_KEY_ALIAS`, `KABLON_KEY_PASSWORD`
(שים לב: מעבר מפתח מחייב הסרה והתקנה מחדש פעם אחת).

## אחסון – יעד 80GB

אנדרואיד לא מאפשר לשריין מחיצה לאפליקציה. Kablon מנהל "תקציב רך" של 80GB לכל הקבצים שלו,
חוסם עבודות חדשות כשהתקציב מלא, ושומר תמיד לפחות 10GB פנויים לטלפון ולמערכת. פירוט מלא במסך הראשי.

## מגבלות ידועות

- זיהוי כפתורים מבוסס על טקסט/תיאור נגישות (עברית + אנגלית). אפליקציות כמו TikTok משנות ממשק
  לעיתים קרובות – כשהאוטומציה לא מוצאת כפתור היא עוצרת, מתריעה, ומחכה שתשלים ידנית.
- הסרת סימן מים: בתמונות – עריכה מחדש במודל AI (עלולה לשנות פרטים / להיות מסורבת);
  בסרטונים – טשטוש אזור עם ffmpeg ב-Termux. השתמש רק בתוכן שבבעלותך.
