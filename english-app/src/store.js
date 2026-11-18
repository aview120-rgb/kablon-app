// On-device state. Nothing here is sent anywhere.
const KEY = 'safa.state.v1';

export function today(d = new Date()) {
  const y = d.getFullYear(), m = String(d.getMonth() + 1).padStart(2, '0'), day = String(d.getDate()).padStart(2, '0');
  return `${y}-${m}-${day}`;
}
export function addDays(dayStr, n) {
  const [y, m, d] = dayStr.split('-').map(Number);
  return today(new Date(y, m - 1, d + n));
}
export function dayDiff(a, b) {
  const [y1, m1, d1] = a.split('-').map(Number), [y2, m2, d2] = b.split('-').map(Number);
  return Math.round((Date.UTC(y2, m2 - 1, d2) - Date.UTC(y1, m1 - 1, d1)) / 86400000);
}
export function isoWeek(dayStr) {
  const [y, m, d] = dayStr.split('-').map(Number);
  const t = new Date(Date.UTC(y, m - 1, d));
  const dow = t.getUTCDay() || 7; t.setUTCDate(t.getUTCDate() + 4 - dow);
  const y0 = new Date(Date.UTC(t.getUTCFullYear(), 0, 1));
  return `${t.getUTCFullYear()}-W${String(Math.ceil(((t - y0) / 86400000 + 1) / 7)).padStart(2, '0')}`;
}

export function fresh() {
  return {
    version: 1,
    profile: { goal: null, level: null, dailyGoalXp: 20, xpTotal: 0, xpByDay: {}, streak: 0, bestStreak: 0,
      lastActiveDay: null, freezes: 1, freezeWeek: null, onboarded: false, adult: null },
    progress: {},
    words: {},
    settings: { sound: true, speakHebrew: true, ttsRate: 0.85, theme: 'auto', reducedMotion: false, fontScale: 1 },
    ads: { lessonsSinceInterstitial: 0, consent: 'unknown' },
  };
}

let mem = null;
const storage = (() => { try { const s = window.localStorage; s.setItem('_t', '1'); s.removeItem('_t'); return s; } catch { return null; } })();

export function load() {
  if (mem) return mem;
  let s = null;
  try { s = JSON.parse(storage?.getItem(KEY) || 'null'); } catch { s = null; }
  const base = fresh();
  mem = s && s.version === 1 ? deepMerge(base, s) : base;
  return mem;
}
export function save() { try { storage?.setItem(KEY, JSON.stringify(mem)); } catch { /* storage full or blocked */ } }
export function reset() { mem = fresh(); try { storage?.removeItem(KEY); } catch {} save(); return mem; }
export function _setForTest(s) { mem = s; }

function deepMerge(a, b) {
  for (const k of Object.keys(b)) {
    if (b[k] && typeof b[k] === 'object' && !Array.isArray(b[k]) && a[k] && typeof a[k] === 'object') a[k] = deepMerge(a[k], b[k]);
    else a[k] = b[k];
  }
  return a;
}

// Streak: call when the user finishes a lesson. Uses local calendar days.
export function touchStreak(state, day = today()) {
  const p = state.profile;
  const wk = isoWeek(day);
  if (p.freezeWeek !== wk) { p.freezeWeek = wk; p.freezes = Math.max(p.freezes, 1); }
  if (p.lastActiveDay === day) return p.streak;
  if (!p.lastActiveDay) p.streak = 1;
  else {
    const gap = dayDiff(p.lastActiveDay, day);
    if (gap === 1) p.streak += 1;
    else if (gap === 2 && p.freezes > 0) { p.freezes -= 1; p.streak += 1; }
    else p.streak = 1;
  }
  p.lastActiveDay = day;
  p.bestStreak = Math.max(p.bestStreak || 0, p.streak);
  return p.streak;
}

// Streak shown on screen: 0 if more than one missed day (and no freeze can cover it).
export function liveStreak(state, day = today()) {
  const p = state.profile;
  if (!p.lastActiveDay) return 0;
  const gap = dayDiff(p.lastActiveDay, day);
  if (gap <= 1) return p.streak;
  if (gap === 2 && p.freezes > 0) return p.streak;
  return 0;
}

export function addXp(state, xp, day = today()) {
  state.profile.xpTotal += xp;
  state.profile.xpByDay[day] = (state.profile.xpByDay[day] || 0) + xp;
}
