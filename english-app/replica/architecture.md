# Architecture

## Stack (chosen for: free to run, no server, easy Google Play upload)
| layer | choice | why |
| --- | --- | --- |
| app | Vanilla JS (ES modules) + Vite 8 | tiny bundle, fast on cheap Android phones, no framework to learn |
| styling | plain CSS with design tokens (replica-design) | RTL + dark mode with CSS variables |
| mobile | Capacitor 8 (Android) | wraps the web app into a real Android app → `.aab` for Google Play |
| storage | localStorage (+ Capacitor Preferences mirror) | no accounts, no server, no database to secure → minimal privacy-law exposure |
| speech | Web Speech API in browser, `@capacitor-community/text-to-speech` on Android | Android WebView has no speechSynthesis |
| ads | `@capacitor-community/admob` 8 (banner, interstitial, rewarded) + Google UMP consent | official Google SDK, pays to your AdSense/AdMob account |
| CI / build | GitHub Actions → signed `.aab` artifact | the Android SDK is installed on the GitHub runner; you just download the file |
| tests | Playwright (web build) | clicks through every flow |

**No backend. One "database" = a JSON object on the device.** This is a deliberate legal choice:
no personal data is collected by us, so Israeli Privacy Protection Law (Amendment 13) duties for a
"database owner" are minimal. The only third party is Google AdMob — disclosed in the privacy policy.

## Schema (on-device JSON, key `safa.state.v1`)
```json
{
  "version": 1,
  "profile": { "goal": "travel|work|school|fun", "level": "A0|A1|A2", "dailyGoalXp": 20,
               "xpTotal": 0, "xpByDay": {"2026-11-16": 30}, "streak": 0, "lastActiveDay": null,
               "freezes": 1, "freezeWeek": "2026-W46", "onboarded": false, "ageConfirmed": false },
  "progress": { "<lessonId>": { "done": true, "best": 0.9, "at": "ISO" } },
  "words":    { "<en>": { "box": 1, "next": "YYYY-MM-DD", "wrong": 0, "seen": 0 } },
  "settings": { "sound": true, "ttsRate": 0.9, "theme": "auto|light|dark", "reducedMotion": false,
                "fontScale": 1 },
  "ads":      { "lessonsSinceInterstitial": 0, "consent": "unknown|obtained|not_required|limited" }
}
```
Rules: days are local-date strings (YYYY-MM-DD); streak math compares local dates only.
Content (units/lessons/exercises) is static JS bundled in the app — never stored per user.

## "API" (module functions, no network)
| function | does | flow |
| --- | --- | --- |
| `store.load()/save()` | read/write state, migrate versions | all |
| `course.units()` / `course.lesson(id)` | static content | F01 F02 |
| `progress.isUnlocked(id)` | lesson i unlocks when i-1 done | F02 |
| `progress.completeLesson(id, acc, xp)` | saves progress, XP, streak, words | F02 F04 |
| `srs.due()` / `srs.grade(word, ok)` | Leitner review (boxes 1..5 → 0,1,3,7,14 days) | F03 |
| `ads.init()/banner()/maybeInterstitial()/rewarded()` | AdMob + consent; no-op on web | F02 F05 |
| `ads.showPrivacyOptions()` | re-open UMP form | F05 |

## The parts that bite
- **Time zones / streak:** use local calendar date, not UTC, or Israeli users lose streaks at 02:00.
- **Consent before ads:** call UMP `requestConsentInfo` → `showConsentForm` *before* the first ad request.
- **Children:** the app is rated 13+; we set `tagForUnderAgeOfConsent=false` only after the age gate; under-13 → non-personalised ads only (`npa`), and Play target audience = 13+ to avoid Families program duties.
- **Ad frequency:** interstitial max once every 3 lessons, never mid-lesson (Play "disruptive ads" policy).
- **Offline:** everything bundled; ads simply fail silently.
- **Data deletion:** Settings → "delete all my data" clears local storage (Play Data-safety requirement).

## Build order
1. Vertical slice: S03 home → S04 lesson (choice exercise) → S05 complete, saves XP.
2. Must-haves: onboarding/consent (S01 S02), all exercise types, streak, review (S06), RTL, a11y, legal (S10), ads.
3. Should: words list (S07), stats chart (S08), dark mode, match exercise.
4. Could: rewarded freeze, reminders.
