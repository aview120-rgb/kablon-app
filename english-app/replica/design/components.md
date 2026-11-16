# Components (original; icons = inline SVG / emoji, no third-party art)

All components: RTL-first (`dir="rtl"`), English text inside gets `dir="ltr" lang="en"`.
Touch target ≥ 48px. Focus-visible: 3px ring `accent`. Respect `prefers-reduced-motion`.

Button        variants primary / secondary / ghost / danger · sizes md 48px, lg 56px
              states default, pressed (translateY 2px, shadow off), focus-visible, disabled (50% opacity, aria-disabled)
              tokens bg accent, text on-accent, radius md, 600 weight · used S01 S04 S05 S09
Chip          selectable option (role=radio / aria-pressed) · selected = accent border + tint · S01 S04
ProgressBar   role=progressbar aria-valuenow · track border, fill accent, radius pill · S04 S08
UnitCard      header with emoji, Hebrew title, English subtitle, n/total done · S03
LessonNode    round 72px · states locked (muted, 🔒, aria-disabled), available (accent, pulse), done (success, ✓) · S03
ExerciseCard  types choice / build / type / listen / match · always shows Hebrew instruction + speaker button · S04
WordTile      button in word bank; moves between bank and answer line · S04
FeedbackSheet bottom sheet, aria-live=assertive · correct (success-soft, "מעולה!") / wrong (danger-soft, shows right answer + Hebrew tip) · S04
StatTile      icon + big number + label · S05 S08
BarChart      7 bars (week XP), each bar has aria-label "יום ב׳: 30 XP" · S08
TabBar        4 tabs: לימוד, תרגול, מילים, פרופיל · aria-current=page · S03 S06 S07 S08
Toggle        role=switch aria-checked · S09
Toast         role=status, 3s · global
AdSlot        reserved 50px bottom area for banner; labelled "פרסומת"; never overlaps buttons · S03
Modal         role=dialog aria-modal, focus trap, Esc closes · S02 S09
