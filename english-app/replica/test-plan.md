# Test plan

Automated: `npm test` (7 unit tests) + `npm run e2e` (14 Playwright flows on a Pixel 7 viewport, each with
console-error guard and axe WCAG 2 A/AA scan).

| id | case | how | result |
| --- | --- | --- | --- |
| F01-H1 | onboarding → path, lesson 1 open, lesson 2 locked | e2e + axe | pass |
| F01-E1 | continue disabled until a choice | e2e | pass |
| F01-E2 | A2 level skips unit 1 | e2e | pass |
| F01-E3 | keyboard-only onboarding | e2e | pass |
| F02-H1 | full lesson → XP, 100%, streak 1, unlock, survives reload | e2e + axe | pass |
| F02-E1 | wrong answer → correct answer + Hebrew tip, retried | e2e | pass |
| F02-E2 | quit mid-lesson → nothing saved | e2e | pass |
| F02-E3 | all wrong → lesson still finite | e2e | pass |
| F02-N1 | locked lesson by URL → redirected | e2e | pass |
| F03-H1/E1 | practice empty state + review session | e2e + axe | pass |
| F04 | streak: same day, next day, freeze, reset; DST & year boundary | unit | pass |
| F05 | settings: dark mode, text size, delete all data | e2e + axe | pass |
| S07 | words search + no results | e2e + axe | pass |
| S08 | profile chart labelled | e2e + axe | pass |
| S10 | 3 legal pages render, accessible | e2e + axe | pass |
| content | every lesson × 5 random builds: answer in options, no duplicate options, sentence solvable from bank | unit | pass |

## Manual (needs a real Android phone – owner)
- [ ] Install the test APK from GitHub Actions → onboarding → Google consent form appears (in EEA via VPN) or not (Israel)
- [ ] "Test Ad" banner shows on home tab, hidden inside lessons
- [ ] Interstitial after the 3rd completed lesson, not before
- [ ] Rewarded video in Practice gives a streak freeze
- [ ] English voice speaks (if not: Android Settings → Text-to-speech → install English)
- [ ] TalkBack reads buttons in Hebrew and English words in English
