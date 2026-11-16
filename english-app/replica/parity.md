## Parity: 96.8 / 100

features 96.8  (25 counted, must-haves 15 of 15 done)

## By area, weakest first
- motivation                    83.3  (3 features)
- lesson                        95.0  (8 features)
- onboarding                   100.0  (2 features)
- home                         100.0  (2 features)
- profile                      100.0  (1 features)
- practice                     100.0  (2 features)
- platform                     100.0  (3 features)
- monetization                 100.0  (3 features)
- legal                        100.0  (1 features)

## Missing, in build order
- [could] lesson: Speech recognition grading, no  (later version)
- [could] motivation: Local reminders, no

## Left out on purpose (not scored)
- Social leagues and friends: needs server; privacy risk
- Human corrections community: needs moderation
- Paid subscription: user wants free + ads

## Yours, not in the original (not scored)
- Hebrew hint/explanation per exercise
- Hebrew false-friends and pronunciation tips unit
- RTL Hebrew interface

## Layout diff
Not run against the category leaders on purpose: we saved no screenshots of other apps, and our layout is our
own (RTL, own palette, own icon). Clone screenshots for our own regression checks are in `replica/clone-screens/`.

## Behaviour diff
| flow | category leaders | Safa | keep / fix |
| --- | --- | --- | --- |
| start learning | sign-up wall or account prompt early | no account at all, 4 taps to lesson 1 | keep (win) |
| mistakes | correct answer shown | correct answer + a Hebrew grammar tip | keep (win) |
| streak lost | paid / gem-bought repairs | 1 free freeze a week + optional rewarded ad | keep |
| ads | some show ads mid-lesson or after every lesson | never inside a lesson, max 1 full-screen ad per 3 lessons | keep (win) |
| offline | partial, premium feature in some | everything offline, free | keep (win) |
| voice | recorded audio | device text-to-speech (depends on installed voice) | fix later: offer recorded audio for top 200 words |

## Verdict
**Shippable** – all 15 must-haves done, feature score 96.8, no open S1/S2.

## Next five to build
1. Daily reminder notification (local, opt-in)
2. Speaking practice with speech recognition
3. Units 10–15 (B1): past tense, future, phone calls, job interview
4. Recorded native audio for the 200 most common words
5. Placement test instead of self-reported level
