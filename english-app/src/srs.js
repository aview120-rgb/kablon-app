// Leitner spaced repetition. Box 1..5 -> review again after 0,1,3,7,14 days.
import { today, addDays } from './store.js';
const GAP = [0, 0, 1, 3, 7, 14];

export function seeWord(state, en, ok, day = today()) {
  const w = state.words[en] || (state.words[en] = { box: 1, next: day, wrong: 0, seen: 0 });
  w.seen += 1;
  if (ok) w.box = Math.min(5, w.box + 1);
  else { w.box = 1; w.wrong += 1; }
  w.next = addDays(day, GAP[w.box]);
  return w;
}
export function due(state, day = today()) {
  return Object.entries(state.words).filter(([, w]) => w.next <= day).sort((a, b) => a[1].box - b[1].box || b[1].wrong - a[1].wrong).map(([en]) => en);
}
