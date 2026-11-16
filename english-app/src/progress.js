import { allLessons } from './data/course.js';
import { touchStreak, addXp } from './store.js';

export function isDone(state, id) { return !!state.progress[id]?.done; }
export function isUnlocked(state, id) {
  const ls = allLessons(); const i = ls.findIndex((l) => l.id === id);
  if (i <= 0) return i === 0;
  return isDone(state, ls[i - 1].id) || isDone(state, id);
}
export function nextLesson(state) { return allLessons().find((l) => !isDone(state, l.id)) || null; }
export function xpFor(accuracy) { return 10 + Math.round(accuracy * 10); }
export function completeLesson(state, id, accuracy) {
  const prev = state.progress[id];
  const xp = prev?.done ? 5 + Math.round(accuracy * 5) : xpFor(accuracy);
  state.progress[id] = { done: true, best: Math.max(prev?.best || 0, accuracy), at: new Date().toISOString() };
  addXp(state, xp);
  const streak = touchStreak(state);
  return { xp, streak };
}
