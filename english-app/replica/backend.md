# Backend (replica-backend)

By design there is **no server, no accounts, no payments**. This keeps the app free to run (₪0/month) and
keeps legal exposure low: the operator holds no database of personal data (Israeli Privacy Protection Law,
Amendment 13). The only "backend" is Google AdMob, which the user signs up for themselves.

| part | implementation |
| --- | --- |
| data | `src/store.js` – one JSON object in localStorage, versioned, "delete all my data" in Settings |
| ads | `src/ads.js` – `@capacitor-community/admob` 8 (Google Mobile Ads SDK) |
| consent | Google UMP: `requestConsentInfo` → `showConsentForm` (if required) → `initialize` → ads. Privacy options form re-openable in Settings |
| minors | age question in onboarding → under 18: `npa` (non-personalised), `tagForUnderAgeOfConsent`; under 13: `tagForChildDirectedTreatment`, max rating G |
| ad pacing | banner only on tab screens (never inside a lesson); interstitial only on lesson-complete, max 1 per 3 lessons; rewarded only on user tap |
| keys | AdMob ids in `src/config.js` + `ADMOB_APP_ID` Gradle property / GitHub secret. Defaults are Google's public **test** ids |

## Accounts the owner creates (Claude never does this)
1. Google Play Console developer account – one-time $25.
2. AdMob account (admob.google.com) linked to the same Google account → create app → 3 ad units (banner, interstitial, rewarded).
3. In AdMob: Privacy & messaging → create a GDPR message (EEA/UK) so the UMP form has something to show.
4. Payments → add Israeli bank account (IBAN) and tax info (W-8BEN for non-US individuals).

## Security checklist
- [x] secrets only in env vars / GitHub secrets, keystore & `.env*` in `.gitignore`
- [x] no server → no input validation / auth / rate-limit surface
- [x] no user data in URLs or logs (we log nothing)
- [x] no webhooks, no uploads
- [x] `android:allowBackup=false`, WebView debugging off in release, no mixed content
- [x] dependencies audited (`npm audit` – see below)
- [x] privacy policy lists every processor (Google AdMob only)
- [x] `.env.example` not needed: no runtime secrets (ad unit ids are public by nature)
