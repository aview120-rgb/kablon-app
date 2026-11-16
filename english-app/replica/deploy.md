# Deploy (replica-deploy) – preflight results 2026-11-16

```
## npm test
# pass 7
# fail 0
## e2e
  14 passed (23.3s)
## parity
Parity: 96.8 / 100

features 96.8  (25 counted, must-haves 15 of 15 done)
## sweep
Clean. Nothing of the original's name, domain or colours found.
## listing
0 errors, 0 warnings
## build
✓ built in 177ms
```
After bug fixes B04/B05: e2e 15/15 pass.

| by hand | result |
| --- | --- |
| open S1/S2 bugs | none |
| privacy policy / terms / accessibility pages | written; **owner must fill name + email**; needs a public URL for Play |
| processors listed | Google AdMob (only one) |
| data deletion | Settings → delete all data (e2e tested) |
| icon, titles, store images | ours (sweep clean) |
| production ad ids | **owner** – see docs/ADMOB-SETUP.md |
| signing key | **owner** runs the one-time workflow – see docs/GOOGLE-PLAY.md |

Mobile: no local Android SDK in this environment (Google's download host is blocked), so the `.aab` is built by
GitHub Actions (`.github/workflows/safa-android.yml`). Nothing was published: the owner uploads to Play Console.

## First week to watch
Play Console → Android vitals (crashes, ANRs) · AdMob → fill rate & policy center · reviews mentioning voice/TTS.
