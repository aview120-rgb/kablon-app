# Bugs

| id | sev | title | status |
| --- | --- | --- | --- |
| B01 | S3 | Lesson buttons in every unit had the same accessible name ("שיעור 1: …") – screen-reader users could not tell units apart | fixed: label now includes unit name |
| B02 | S4 | Programmatic focus on page heading drew a focus ring around the title | fixed: no outline for `tabindex=-1` |
| B03 | S4 | Welcome title rendered "Safa־" with a stray maqaf in RTL | fixed |

Open S1/S2: **none**.

## To check (not reproduced)
- Native banner overlapping the tab bar on very small phones (needs a real device; CSS reserves 64px).
