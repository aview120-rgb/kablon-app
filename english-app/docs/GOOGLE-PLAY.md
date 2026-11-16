# העלאה ל־Google Play – צעד אחר צעד 🚀

לא צריך להתקין כלום על המחשב: GitHub בונה את קובץ האפליקציה בשבילך.

## 1. מפתח חתימה (פעם אחת בלבד)
1. ב־GitHub → לשונית **Actions** → **"Safa – create Google Play upload key (run ONCE)"** → Run workflow.
2. כשמסתיים (✅) → נכנסים להרצה → למטה Artifacts → מורידים `safa-upload-key-…`.
3. בתוך ה־zip יש `READ-ME-SECRETS.txt`. מוסיפים את 4 הסודות שכתובים שם:
   Settings → Secrets and variables → Actions → New repository secret.
4. **שומרים את ה־zip במקום בטוח** (Google Drive) ומוחקים את ההרצה מ־GitHub.

## 2. בניית האפליקציה
1. Actions → **"Safa – build Android app (Google Play)"** → Run workflow.
   - Version code: `1` (בכל העלאה הבאה: 2, 3, 4…)
   - Version name: `1.0.0`
2. בסוף מורידים:
   - `safa-google-play-bundle` → בתוכו קובץ **`.aab`** – זה מה שמעלים לחנות.
   - `safa-test-apk-install-on-phone` → קובץ **`.apk`** להתקנה ובדיקה בטלפון שלך.

## 3. Play Console
1. https://play.google.com/console → הרשמה כמפתח ($25 חד פעמי, אימות זהות).
2. Create app → שם: `Safa – לומדים אנגלית בעברית` · שפה ברירת מחדל: עברית · App · Free.
3. **Store listing** – מעתיקים מ־`replica/launch/listing.json`:
   - תיאור קצר, תיאור מלא
   - אייקון 512×512: `public/icon-512.png`
   - תמונה ראשית 1024×500: `store/feature-graphic.png`
   - צילומי מסך: `store/screenshots/` (5 תמונות)
   - קטגוריה: Education
4. **App content** (הצהרות):
   | שאלה | תשובה |
   | --- | --- |
   | Privacy policy | הקישור לדף מדיניות הפרטיות (ראו סעיף 4) |
   | Ads | **Yes, my app contains ads** |
   | App access | All functionality is available without special access |
   | Content rating | שאלון → Education, בלי אלימות/תוכן מיני → צפוי Everyone / PEGI 3 |
   | Target audience | **13-15, 16-17, 18+** (לא לסמן מתחת ל־13!) · "Appeals to children?" → No |
   | Data safety | Collects data: **Yes** → Device or other IDs (Advertising ID) → Collected, shared with Google AdMob, for Advertising & Analytics, not optional for ad serving; App interactions → Analytics (by AdMob SDK). Encrypted in transit: **Yes**. Users can request deletion: **Yes** (מחיקה בהגדרות) |
   | Advertising ID | **Yes** – used for Advertising |
   | Government app / Financial / Health | No |
5. **Testing → Closed testing**: מעלים את ה־`.aab`, מוסיפים לפחות **12 בודקים** (אימיילים של Gmail) –
   חשבון מפתח פרטי חדש חייב 12 בודקים במשך 14 יום לפני פרסום לכולם.
6. אחרי 14 יום → Production → Create release → אותו `.aab` (או גרסה חדשה) → Send for review.

## 4. כתובת למדיניות הפרטיות
Google דורשת קישור ציבורי. מאחר שה־repo פרטי, GitHub Pages חינמי לא יעבוד. אפשרויות:
- Google Sites (חינם): יוצרים אתר, מדביקים את הטקסט מ־`public/legal/privacy.html`.
- Cloudflare Pages (חינם): להעלות את התיקייה `site/` + `dist/` – אוכל לעשות את זה בשבילך כשתאשר.

## 5. עדכון גרסה בעתיד
מעלים את Version code ב־1 → Run workflow → מעלים את ה־`.aab` החדש ל־Play Console.
