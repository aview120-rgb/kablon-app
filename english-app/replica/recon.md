# Recon map: English-learning apps (category, mechanics only)

> Clean-room. We studied **public** pages and reviews of category leaders
> (Duolingo, Busuu, Babbel) to learn *mechanics that work*. Nothing they own
> (names, mascots, art, course text, sounds, colours, layouts) is used.
> Our app is original, built for **Hebrew speakers learning English**.

## Scope
- Platform: Android (Google Play) first, built as a web app wrapped with Capacitor. Web/PWA works too.
- Slice: the core loop – *pick a unit → short lesson (5–8 exercises) → feedback → XP / streak → next lesson*.
- Audience: Hebrew-speaking teens (13+) and adults, beginner → intermediate (A1–B1).

## Sources
| source | url | what it gave |
| --- | --- | --- |
| Clozemaster comparison 2026 | https://www.clozemaster.com/blog/?p=7678 | Busuu = corrections, Babbel = structured beginner course |
| Toolradar guide | https://toolradar.com/guides/best-apps-to-learn-english | free full A1–C1 course is the top value driver |
| getfluently guide | https://getfluently.app/blog/best-english-learning-apps | no single best app; speaking practice is the gap |
| elitedor.dz test | https://elitedor.dz/best-apps-to-learn-english-2026/ | leaders target L1 pronunciation errors — none target Hebrew |
| learnenglish.life compare | https://learnenglish.life/compare/best-apps-to-learn-english/ | paid apps ≈ $14/month → room for a free, ad-supported one |

**Conclusion of research:** the market leader for habit + free is Duolingo; Busuu wins on structure. **No major app is
built *for Hebrew speakers*** (RTL explanations, Hebrew false-friends, th/w/v pronunciation issues). That is our gap.

## Screen inventory
| ID | screen | how to get there | purpose | key components | states |
| --- | --- | --- | --- | --- | --- |
| S01 | Welcome / onboarding | first launch | pick goal + level | hero, chips, button | first-run |
| S02 | Consent / privacy | after S01 | privacy + ad consent | text, toggles, link | EEA (UMP form) / non-EEA |
| S03 | Home – learning path | tab | units and lessons | unit card, lesson node, progress ring | empty (new), filled, all-done |
| S04 | Lesson | tap lesson | exercises | progress bar, exercise card, check button, feedback sheet | correct, wrong, loading audio, no TTS |
| S05 | Lesson complete | end of S04 | XP, accuracy, streak | stats, confetti, continue, interstitial ad slot | ad shown / no ad |
| S06 | Practice (review) | tab | spaced review of mistakes & words | flashcard, flip, know/don't know | empty (nothing to review) |
| S07 | Dictionary / my words | tab | words learned, listen | search, list rows, speaker icon | empty, filled, no results |
| S08 | Profile & stats | tab | streak, XP week chart, daily goal | stat tiles, bar chart | new user |
| S09 | Settings | from S08 | sound, reminders, goal, reset, privacy, accessibility | toggles, links | — |
| S10 | Legal pages | from S09 / S02 | privacy policy, terms, accessibility statement | long text | — |

## Flows
```
F01 New user starts learning (happy path = 4 taps)
    S01 goal -> S01 level -> S02 accept -> S03 -> S04
    edge: user declines ad personalisation; no TTS voice on device
F02 Complete a lesson
    S04 x5-8 exercises -> S05 -> (interstitial ad, max 1 per 3 lessons) -> S03
    edge: user quits mid-lesson (progress not saved), all wrong answers, offline
F03 Review mistakes
    S06 -> cards -> done
    edge: nothing to review
F04 Daily streak
    finish 1 lesson/day -> streak +1 ; miss a day -> reset (1 free "freeze" a week)
F05 Change privacy / ads choice
    S09 -> privacy options -> UMP form re-opens
```

## Components
button (primary/secondary/ghost; default/pressed/disabled), chip, progress bar, unit card, lesson node
(locked/available/done), exercise card (choice / build sentence / type / listen / match), feedback sheet
(correct/wrong), stat tile, bar chart, tab bar, toggle, toast, banner-ad slot, modal.

## Inferred data model (all local, on device)
```
Unit      id, title_he, title_en, emoji, lessons[]              evidence: S03   confidence: high
Lesson    id, unit_id, title_he, exercises[]                    evidence: S04   confidence: high
Exercise  type (choice|build|type|listen|match), prompt, answer, options[], hint_he
Progress  lesson_id -> {done, best_accuracy, completed_at}
Word      en, he, unit_id, seen_count, wrong_count, next_review_at (Leitner box 1-5)
Profile   goal_minutes, level, xp_total, xp_by_day{}, streak, last_active_day, freezes
Settings  sound, tts_rate, reminders, reduced_motion, ads_consent_state
```
No accounts, no server: nothing personal leaves the device except what the AdMob SDK sends (disclosed).

## What we do not clone (skip)
- Course content of any app (we write our own original lessons).
- Mascot, characters, brand colours, sounds, owl/bird shapes.
- Social leagues / friends (needs a server + moderation; also more privacy risk).
- Human tutor corrections (Busuu-style community) – needs moderation.
- Speech-recognition grading (device API is inconsistent; later version).

## Size
10 screens, 5 flows, 7 entities. Hard parts: (1) good original content in Hebrew+English,
(2) AdMob + consent done correctly, (3) Android build/signing pipeline. Size: **M**.
