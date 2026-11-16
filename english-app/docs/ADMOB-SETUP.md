# הגדרת AdMob – כדי לקבל כסף מהפרסומות 💰

כרגע האפליקציה משתמשת ב**מזהי בדיקה** של Google: מופיע "Test Ad" ולא מרוויחים כלום. זה מכוון, כדי שלא תיחסם חשבון בזמן בדיקות.

## שלב 1 – לפתוח חשבון
1. נכנסים ל־https://admob.google.com עם חשבון Google (רצוי אותו חשבון של Play Console).
2. Payments → ממלאים כתובת, חשבון בנק ישראלי (IBAN) ופרטי מס (טופס W-8BEN – אדם פרטי, לא אזרח ארה"ב).

## שלב 2 – ליצור אפליקציה ו־3 יחידות פרסום
1. Apps → Add app → Android → "No, not published yet" → שם: Safa.
2. מעתיקים את **App ID** (נראה כך: `ca-app-pub-1234567890123456~1234567890`).
3. Ad units → יוצרים 3:
   - **Banner** → מעתיקים את המזהה (`ca-app-pub-…/…`)
   - **Interstitial** → מעתיקים
   - **Rewarded** → מעתיקים

## שלב 3 – הודעת פרטיות (חובה לאירופה)
Privacy & messaging → GDPR → Create message → בוחרים את Safa → Publish.
(בלי זה, משתמשים מאירופה לא יראו פרסומות מותאמות.)

## שלב 4 – לשים את המזהים בקוד
1. ב־GitHub: Settings → Secrets and variables → Actions → New repository secret
   - שם: `ADMOB_APP_ID` ערך: ה־App ID משלב 2
2. בקובץ `english-app/src/config.js`:
   ```js
   testMode: false,
   bannerId: 'ca-app-pub-XXXX/BANNER',
   interstitialId: 'ca-app-pub-XXXX/INTERSTITIAL',
   rewardedId: 'ca-app-pub-XXXX/REWARDED',
   ```
   (או לשלוח לי את המזהים ואני אעדכן.)

## ⚠️ אסור!
- **אסור ללחוץ על הפרסומות שלך** או לבקש מחברים ללחוץ – Google חוסמת חשבונות על זה לצמיתות.
- לבדיקות בטלפון שלך – להשאיר `testMode: true`, או להוסיף את הטלפון כ־Test device ב־AdMob.

## כמה כסף?
תלוי בכמות המשתמשים. בישראל זה בערך כמה דולרים לכל 1,000 הצגות (משתנה מאוד). 1,000 משתמשים פעילים ביום ≈ כמה עשרות דולרים בחודש. Google משלמת כשהיתרה עוברת $100.
