# שרת תור משימות — AutoAgent

שרת Node.js קטן **ללא תלויות חיצוניות** ששומר ומעבד משימות גם כשהטלפון כבוי או לא מקוון,
ומוסר לטלפון את כל התוצאות המוכנות ברגע שהוא חוזר לרשת.

## הפעלה מהירה

```bash
cd server
export KABLON_TOKEN="בחרו-אסימון-ארוך-וסודי"
npm start                     # או: node src/index.js
# פתחו בדפדפן: http://localhost:8787  (ממשק ניהול בעברית)
```

דרישות: Node.js 18.17 ומעלה (מומלץ 22). אין צורך ב-`npm install`.

### Docker
```bash
cd server
docker build -t kablon-queue .
docker run -d --name kablon -p 8787:8787 -e KABLON_TOKEN="האסימון-שלכם" -v kablon-data:/data kablon-queue
```

### משתני סביבה
| משתנה | ברירת מחדל | הסבר |
| --- | --- | --- |
| `KABLON_TOKEN` | אקראי בכל הפעלה (מודפס ללוג) | אסימון הגישה. **חובה להגדיר** כדי שהטלפון יוכל להתחבר גם אחרי הפעלה מחדש |
| `PORT` | `8787` | פורט |
| `HOST` | `0.0.0.0` | כתובת האזנה |
| `DATA_FILE` | `./data/db.json` | קובץ הנתונים (נשמר בכתיבה אטומית + גיבוי `.bak`) |
| `TZ_NAME` | `Asia/Jerusalem` | אזור זמן לתבניות `{{date}}`/`{{time}}` |
| `QUIET` | — | כל ערך מכבה את לוג הבקשות |

> לשרת שחשוף לאינטרנט השתמשו ב-HTTPS (למשל מאחורי Caddy/nginx או Cloudflare Tunnel).
> ברשת הביתית אפשר להשתמש ב-`http://192.168.x.x:8787`.

## איך זה עובד

```
 יוצרים משימה (ממשק ניהול / API / מהטלפון עצמו)
        │
        ▼
 queued ──► processing ──► ready ──(סנכרון)──► delivered ──► done / failed / expired
   ▲  שגיאה זמנית: ניסיון חוזר עם השהיה אקספוננציאלית (5ש׳, 10ש׳, 20ש׳… עד 10 דק׳)
   └── משימה חוזרת (repeat) יוצרת את המופע הבא אוטומטית
```

* **עיבוד בזמן שהטלפון לא מקוון:** השרת מעבד כל משימה בזמן שלה. הודעות ותזכורות מוכנות **מראש** (24 שעות לפני
  כברירת מחדל, `leadMs`), כך שהטלפון מקבל אותן כשהוא מחובר ומבצע אותן בזמן גם אם בדיוק אז אין לו רשת.
* **סנכרון אוטומטי בחזרה לרשת:** הטלפון שולח בקשת `POST /api/v1/sync` אחת שמכילה את כל מה שנאסף אצלו בזמן
  הניתוק (תוצאות ביצוע, טקסט התראות אם הופעל, משימות שנוצרו בטלפון) ומקבל בחזרה את כל המשימות המוכנות.
* **אידמפוטנטיות:** כל פריט מאושר לפי מזהה (`clientId`, `received`, `acceptedResults`), כך שניתוק באמצע או
  שליחה כפולה לעולם לא יוצרים כפילות.
* **Long-poll:** כשהאפליקציה פתוחה היא ממתינה ב-`GET /api/v1/wait` ומקבלת משימות חדשות תוך שניות.
* **שרידות:** הנתונים נשמרים לקובץ בכתיבה אטומית (temp + fsync + rename) עם גיבוי; משימה שנקטעה באמצע עיבוד
  חוזרת לתור בהפעלה הבאה.

## סוגי משימות

| `type` | מה השרת עושה | מה הטלפון עושה |
| --- | --- | --- |
| `send_message` | ממלא תבנית, בודק נמען יחיד ואורך | מתזמן שליחה (SMS / WhatsApp / אפליקציה). לוחץ "שלח" **רק** אם בטלפון הופעל "השרת רשאי ללחוץ שלח"; אחרת מכין בלבד |
| `prepare_message` | כמו למעלה | פותח את הצ'אט עם הטקסט מוכן — אתם לוחצים שלח |
| `reminder` | ממלא תבנית | מציג התראה בזמן |
| `digest` | מסכם את ההתראות שהטלפון העלה (שולחים, שאלות, סכומי כסף) | מציג את הסיכום |
| `fetch_url` | בודק אתר מהשרת (סטטוס, כותרת, טקסט מבוקש) — גם כשהטלפון כבוי | מציג את התוצאה |

תבניות טקסט: `{{date}}`, `{{time}}`, `{{weekday}}` (למשל "יום שישי"), `{{hebrew_date}}`, `{{occurrence}}`,
`{{vars.שם}}` (מתוך `payload.vars`).

## דוגמאות API

```bash
T="Authorization: Bearer $KABLON_TOKEN"; S=http://localhost:8787

# הודעת וואטסאפ מחר ב-08:00 (שעון ישראל) – תוכן עם תבנית
curl -X POST $S/api/v1/tasks -H "$T" -H 'Content-Type: application/json' -d '{
  "type": "send_message",
  "runAt": "2026-11-28T08:00:00+02:00",
  "payload": { "channel": "WHATSAPP", "recipient": "050-1234567", "recipientLabel": "אמא",
               "text": "בוקר טוב! {{weekday}}, {{date}}", "maxLateMinutes": 30 }
}'

# תזכורת כל שעה, 5 פעמים
curl -X POST $S/api/v1/tasks -H "$T" -H 'Content-Type: application/json' \
  -d '{"type":"reminder","payload":{"text":"לשתות מים ({{occurrence}})"},"repeat":{"everyMinutes":60,"count":5}}'

# בדיקת אתר כל 30 דקות
curl -X POST $S/api/v1/tasks -H "$T" -H 'Content-Type: application/json' \
  -d '{"type":"fetch_url","payload":{"url":"https://example.com","contains":"Example"},"repeat":{"everyMinutes":30}}'

curl $S/api/v1/tasks -H "$T"            # רשימת משימות
curl -X DELETE $S/api/v1/tasks/<id> -H "$T"   # ביטול (גם אם כבר נמסרה לטלפון)
curl $S/api/v1/devices -H "$T"          # מכשירים: סוללה, רשת, נגישות, נראה לאחרונה
```

| נתיב | הסבר |
| --- | --- |
| `GET /health` | בדיקת חיים (ללא אסימון) |
| `GET /` | ממשק ניהול בעברית (RTL) |
| `GET /api/v1/meta` | סוגי משימות ומצבים בעברית |
| `POST /api/v1/tasks` | יצירת משימה (או מערך של עד 100) |
| `GET /api/v1/tasks[?deviceId=&status=&limit=]` | רשימה |
| `GET/DELETE /api/v1/tasks/:id` | משימה / ביטול |
| `GET /api/v1/devices` | מכשירים ומצבם האחרון |
| `POST /api/v1/sync` | סנכרון דו-כיווני (משמש את האפליקציה) |
| `GET /api/v1/wait?deviceId=&timeout=25` | Long-poll |

שדות משימה: `type`, `runAt` (מילישניות או ISO), `deviceId` (ריק = המכשיר הראשון שמסתנכרן), `payload`,
`repeat: {everyMinutes, count?}`, `leadMs`, `maxAttempts` (ברירת מחדל 5), `title`.

שגיאות מוחזרות כ-`{"error": "הודעה בעברית"}` עם קוד 400/401/404/413/500.

## בדיקות
```bash
cd server && npm test     # node:test – תור, עיבוד, ניסיונות חוזרים, סנכרון, אחסון, HTTP
```
