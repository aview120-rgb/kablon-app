# Bugs

| id | sev | title | status |
| --- | --- | --- | --- |
| B01 | S3 | Lesson buttons in every unit had the same accessible name ("שיעור 1: …") – screen-reader users could not tell units apart | fixed: label now includes unit name |
| B02 | S4 | Programmatic focus on page heading drew a focus ring around the title | fixed: no outline for `tabindex=-1` |
| B03 | S4 | Welcome title rendered "Safa־" with a stray maqaf in RTL | fixed |

Open S1/S2: **none**.

## To check (not reproduced)
- Native banner overlapping the tab bar on very small phones (needs a real device; CSS reserves 64px).
| B04 | S3 | Feedback sheet stayed on screen after leaving a lesson with the back button | fixed + e2e test |
| B05 | S4 | English phrases inside Hebrew tips put punctuation on the wrong side | fixed: English runs isolated |
| B06 | S2 | No sound on Android when the phone's speech engine has no English voice – app stayed silent | fixed: tries en-US/GB/AU/IN, warms the engine at start, shows Hebrew help + 'install English voice' button, sound test in Settings; e2e test |
| B07 | S1 | Speech hung forever on Android: the Capacitor plugin proxy was returned from an async function, so JS awaited its fake `then` | fixed: plugin kept in a wrapper object |
| B08 | S2 | Native ad banner covered the tab bar; app drew under the phone's navigation buttons | fixed: banner at margin 0 above system bar, real banner height read from SDK, tab bar placed above it, safe-area insets honoured |
