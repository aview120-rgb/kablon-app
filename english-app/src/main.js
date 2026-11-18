import './styles.css';
import { UNITS, allLessons, findLesson, allWords } from './data/course.js';
import * as store from './store.js';
import { isDone, isUnlocked, nextLesson, completeLesson } from './progress.js';
import { buildLesson, buildReview, check, shuffle } from './lesson-engine.js';
import { seeWord, due } from './srs.js';
import { speak, ttsAvailable, warmUp, openVoiceInstall } from './tts.js';
import * as ads from './ads.js';
import { APP } from './config.js';

const app = document.getElementById('app');
const live = document.getElementById('live');
let state = store.load();

// ---------- tiny DOM helper ----------
function h(tag, attrs = {}, ...kids) {
  const el = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs || {})) {
    if (v === false || v == null) continue;
    if (k === 'class') el.className = v;
    else if (k.startsWith('on')) el.addEventListener(k.slice(2), v);
    else if (k === 'text') el.textContent = v;
    else el.setAttribute(k, v === true ? '' : v);
  }
  for (const c of kids.flat()) if (c != null && c !== false) el.append(c.nodeType ? c : document.createTextNode(String(c)));
  return el;
}
const en = (text, extra = {}) => h('span', { lang: 'en', dir: 'ltr', ...extra }, text);
// Hebrew text with English phrases inside: isolate each English run so punctuation lands in the right place.
function mixed(str) {
  return String(str).split(/([A-Za-z][A-Za-z0-9 '’,?!…\-]*[A-Za-z0-9?!]|[A-Za-z])/).filter(Boolean).map((part) => /^[A-Za-z]/.test(part) ? en(part) : part);
}
function announce(msg) { live.textContent = ''; setTimeout(() => (live.textContent = msg), 30); }
function toast(msg) {
  const t = h('div', { class: 'toast', role: 'status' }, msg);
  document.body.append(t); setTimeout(() => t.remove(), 2800);
}
function persist() { store.save(); }
let voiceHelpShown = false;
async function say(text, rate = state.settings.ttsRate, force = false) {
  if (!state.settings.sound && !force) return 'off';
  const st = await speak(text, rate);
  if (st !== 'ok' && (!voiceHelpShown || force)) { voiceHelpShown = true; voiceHelp(st); }
  return st;
}
// Explains in Hebrew why there is no sound, with a button to install an English voice.
function voiceHelp(status) {
  const close = () => back.remove();
  const msg = status === 'no-english'
    ? 'בטלפון שלך לא מותקן קול באנגלית, ולכן אי אפשר להשמיע את המילים.'
    : 'לא נמצא בטלפון מנוע הקראה (Text-to-speech), ולכן אי אפשר להשמיע את המילים.';
  const back = h('div', { class: 'modal-back', onclick: (e) => { if (e.target === back) close(); } },
    h('div', { class: 'modal stack', role: 'dialog', 'aria-modal': 'true', 'aria-labelledby': 'vh-t' },
      h('h2', { id: 'vh-t' }, '🔇 אין קול באנגלית'),
      h('p', {}, msg),
      h('ol', { style: 'padding-inline-start:20px;margin:0' },
        h('li', {}, 'לחצו על "התקנת קול באנגלית" למטה.'),
        h('li', {}, 'בחרו English (United States) והורידו.'),
        h('li', {}, 'אם לא נפתח כלום: הגדרות הטלפון ← חפשו "המרת טקסט לדיבור" ← מנוע מועדף: Google ← התקנת נתוני קול ← English.'),
        h('li', {}, 'אם אין "Google" ברשימה: התקינו מ־Google Play את "Speech Services by Google".')),
      h('button', { class: 'btn', onclick: async () => { const ok = await openVoiceInstall(); if (!ok) toast('פתחו ידנית: הגדרות ← המרת טקסט לדיבור'); } }, '⬇️ התקנת קול באנגלית'),
      h('button', { class: 'btn secondary', onclick: close }, 'סגירה')));
  document.body.append(back);
  back.querySelector('.btn').focus();
}
function applySettings() {
  const r = document.documentElement;
  if (state.settings.theme === 'auto') r.removeAttribute('data-theme'); else r.setAttribute('data-theme', state.settings.theme);
  r.style.setProperty('--font-scale', state.settings.fontScale);
  r.classList.toggle('rm', !!state.settings.reducedMotion);
}

// ---------- router ----------
const routes = { welcome, home, lesson, done: doneScreen, practice, words, profile, settings };
let current = null;
function go(path) { if (location.hash === '#/' + path) render(); else location.hash = '#/' + path; }
function render() {
  const [name, arg] = location.hash.replace(/^#\/?/, '').split('/');
  let r = routes[name] ? name : 'home';
  if (!state.profile.onboarded && r !== 'welcome') r = 'welcome';
  current = r;
  document.querySelectorAll('.sheet, .modal-back').forEach((x) => x.remove());
  const tabs = ['home', 'practice', 'words', 'profile', 'settings'].includes(r);
  document.body.classList.toggle('no-tabs', !tabs);
  app.replaceChildren(routes[r](arg));
  renderTabs(r);
  if (tabs) ads.resumeBanner(); else ads.hideBanner();
  const h1 = app.querySelector('h1, .instr');
  if (h1) { h1.setAttribute('tabindex', '-1'); h1.focus({ preventScroll: true }); }
  window.scrollTo(0, 0);
}
window.addEventListener('hashchange', render);

function renderTabs(r) {
  const bar = document.getElementById('tabbar');
  const items = [['home', '📚', 'לימוד'], ['practice', '🔁', 'תרגול'], ['words', '📖', 'מילים'], ['profile', '⭐', 'פרופיל']];
  bar.replaceChildren(h('nav', { 'aria-label': 'ניווט ראשי' }, items.map(([id, em, label]) =>
    h('a', { href: '#/' + id, 'aria-current': (r === id || (r === 'settings' && id === 'profile')) ? 'page' : false },
      h('span', { class: 'em', 'aria-hidden': 'true' }, em), label))));
}

// ---------- S01/S02 onboarding + consent ----------
function welcome() {
  const draft = { step: 0, goal: null, level: null, age: null };
  const root = h('main', { id: 'main' });
  const goals = [['travel', '✈️', 'לטייל בחו״ל'], ['work', '💼', 'לעבודה'], ['school', '🎓', 'ללימודים ובחינות'], ['fun', '😊', 'סתם בשביל הכיף']];
  const levels = [['A0', '🌱', 'מתחיל לגמרי', 'מכיר רק כמה מילים'], ['A1', '🌿', 'יודע קצת', 'מבין מילים ומשפטים פשוטים'], ['A2', '🌳', 'בינוני', 'מסתדר בשיחה בסיסית']];
  const ages = [['child', 'מתחת לגיל 13'], ['teen', '13 עד 17'], ['adult', '18 ומעלה']];

  const pick = (list, key, render2) => h('div', { class: 'options', role: 'radiogroup' }, list.map((it) =>
    h('button', { class: 'chip', role: 'radio', 'aria-checked': String(draft[key] === it[0]), onclick: () => { draft[key] = it[0]; draw(); } }, render2(it))));

  function draw() {
    let body;
    if (draft.step === 0) {
      body = [h('div', { class: 'hero', 'aria-hidden': 'true' }, '🗣️'),
        h('h1', { class: 'center' }, 'ברוכים הבאים ל', en('Safa'), '!'),
        h('p', { class: 'center muted' }, 'לומדים אנגלית בעברית, 5 דקות ביום. חינם, בלי הרשמה, ועובד גם בלי אינטרנט.'),
        h('div', { class: 'footer-action' }, h('button', { class: 'btn', onclick: () => { draft.step = 1; draw(); } }, 'בואו נתחיל'))];
    } else if (draft.step === 1) {
      body = [h('h1', {}, 'למה את/ה לומד/ת אנגלית?'), pick(goals, 'goal', ([, em, t]) => [h('span', { class: 'em', 'aria-hidden': 'true' }, em), t]),
        h('div', { class: 'footer-action' }, h('button', { class: 'btn', disabled: !draft.goal, onclick: () => { draft.step = 2; draw(); } }, 'המשך'))];
    } else if (draft.step === 2) {
      body = [h('h1', {}, 'מה הרמה שלך?'), pick(levels, 'level', ([, em, t, d]) => [h('span', { class: 'em', 'aria-hidden': 'true' }, em), h('span', {}, h('b', {}, t), h('br'), h('small', { class: 'muted' }, d))]),
        h('div', { class: 'footer-action' }, h('button', { class: 'btn', disabled: !draft.level, onclick: () => { draft.step = 3; draw(); } }, 'המשך'))];
    } else {
      body = [h('h1', {}, 'עוד רגע מתחילים'),
        h('p', {}, 'בן/בת כמה את/ה? זה עוזר לנו להציג פרסומות מתאימות לגיל.'),
        pick(ages, 'age', ([, t]) => t),
        draft.age === 'child' ? h('p', { class: 'tip' }, 'מתחת לגיל 13 צריך הסכמה של הורה. לא יוצגו פרסומות מותאמות אישית.') : null,
        h('div', { class: 'card stack', style: 'margin-top:16px' },
          h('h2', {}, 'פרטיות בקצרה'),
          h('p', {}, 'אנחנו לא אוספים שם, טלפון או אימייל. ההתקדמות שלך נשמרת רק במכשיר שלך.'),
          h('p', {}, 'האפליקציה חינמית בזכות פרסומות של Google AdMob. Google עשויה להשתמש במזהה הפרסום של המכשיר. אפשר לשנות את הבחירה בכל רגע בהגדרות.'),
          h('p', {}, h('a', { href: 'legal/privacy.html' }, 'מדיניות הפרטיות'), ' · ', h('a', { href: 'legal/terms.html' }, 'תנאי שימוש'))),
        h('div', { class: 'footer-action' }, h('button', { class: 'btn', disabled: !draft.age, onclick: finish }, 'מסכים/ה, בואו נלמד!'))];
    }
    const back = draft.step > 0 ? h('button', { class: 'icon-btn', 'aria-label': 'חזרה', onclick: () => { draft.step -= 1; draw(); } }, '→') : null;
    root.replaceChildren(...[back, ...body].filter(Boolean));
    const t = root.querySelector('h1'); if (t) { t.tabIndex = -1; t.focus({ preventScroll: true }); }
  }
  async function finish() {
    Object.assign(state.profile, { goal: draft.goal, level: draft.level, adult: draft.age === 'adult' ? true : draft.age, onboarded: true });
    if (draft.level === 'A2') { for (const l of UNITS[0].lessons) state.progress[l.id] = { done: true, best: 1, at: new Date().toISOString(), skipped: true }; }
    persist();
    await ads.initAds(state); persist();
    go('home');
    ads.showBanner();
  }
  draw();
  return root;
}

// ---------- S03 home / path ----------
function topbar() {
  const s = store.liveStreak(state);
  const todayXp = state.profile.xpByDay[store.today()] || 0;
  return h('div', { class: 'topbar' },
    h('span', { class: 'pill streak', 'aria-label': `רצף של ${s} ימים` }, '🔥 ', s),
    h('span', { class: 'pill', 'aria-label': `${todayXp} מתוך ${state.profile.dailyGoalXp} נקודות היום` }, '⚡ ', `${todayXp}/${state.profile.dailyGoalXp}`),
    h('span', { class: 'spacer' }),
    h('a', { class: 'icon-btn', href: '#/settings', 'aria-label': 'הגדרות' }, '⚙️'));
}
function home() {
  const nxt = nextLesson(state);
  const main = h('main', { id: 'main' }, topbar(), h('h1', { class: 'sr-only' }, 'מסלול הלימוד'));
  if (!nxt) main.append(h('div', { class: 'card center stack' }, h('div', { class: 'hero', 'aria-hidden': 'true' }, '🏆'), h('h2', {}, 'סיימת את כל השיעורים!'), h('p', { class: 'muted' }, 'כל הכבוד. המשיכו לתרגל כדי לא לשכוח – שיעורים חדשים יגיעו בעדכון הבא.'), h('a', { class: 'btn', href: '#/practice' }, 'לתרגול')));
  for (const u of UNITS) {
    const doneCount = u.lessons.filter((l) => isDone(state, l.id)).length;
    main.append(h('section', { class: 'unit', 'aria-labelledby': 'h-' + u.id },
      h('div', { class: 'unit-head' }, h('span', { class: 'em', 'aria-hidden': 'true' }, u.emoji),
        h('div', {}, h('h2', { id: 'h-' + u.id, style: 'margin:0' }, u.he), h('small', {}, en(u.en), ` · ${doneCount}/${u.lessons.length}`))),
      h('div', { class: 'path' }, u.lessons.map((l, i) => {
        const d = isDone(state, l.id), open = isUnlocked(state, l.id), cur = nxt && nxt.id === l.id;
        const cls = `node ${d ? 'done' : open ? 'available' : 'locked'} ${cur ? 'current' : ''}`;
        return h('button', { class: cls, 'aria-disabled': String(!open), 'data-lesson': l.id,
          'aria-label': `${u.he}, שיעור ${i + 1}: ${l.he}. ${d ? 'הושלם' : open ? 'פתוח' : 'נעול'}`,
          onclick: () => open ? go('lesson/' + l.id) : toast('סיימו קודם את השיעור הקודם 🔒') },
          h('span', { class: 'dot', 'aria-hidden': 'true' }, d ? '✓' : open ? '★' : '🔒'), h('span', { class: 'label' }, mixed(l.he)));
      }))));
  }
  return main;
}

// ---------- S04 lesson (also used for review) ----------
let session = null;
function startSession(kind, id, exercises) {
  session = { kind, id, queue: exercises.map((e) => ({ ...e })), total: exercises.length, i: 0, correct: 0, answered: 0, retried: new Set(), mistakes: [] };
}
function lesson(id) {
  const l = findLesson(id);
  if (id === 'review') { if (!session || session.kind !== 'review') return practice(); }
  else {
    if (!l || !isUnlocked(state, id)) { setTimeout(() => go('home')); return h('main'); }
    if (!session || session.id !== id || session.finished) startSession('lesson', id, buildLesson(l));
  }
  const main = h('main', { id: 'main' });
  drawExercise(main);
  return main;
}

function drawExercise(main) {
  const s = session, ex = s.queue[s.i];
  window.__safaCurrent = ex; // used by automated tests
  const pct = Math.round((s.i / s.queue.length) * 100);
  const quit = h('button', { class: 'icon-btn', 'aria-label': 'יציאה מהשיעור', onclick: () => {
    if (confirm('לצאת מהשיעור? ההתקדמות בשיעור הזה לא תישמר.')) { session = null; go(s.kind === 'review' ? 'practice' : 'home'); } } }, '✕');
  const bar = h('div', { class: 'bar', role: 'progressbar', 'aria-valuemin': '0', 'aria-valuemax': '100', 'aria-valuenow': String(pct), 'aria-label': 'התקדמות בשיעור' }, h('i', { style: `width:${pct}%` }));
  let response = null;
  const checkBtn = h('button', { class: 'btn', disabled: true, onclick: () => submit() }, 'בדיקה');
  const setResponse = (v) => { response = v; checkBtn.disabled = v == null || v === '' || (Array.isArray(v) && !v.length); };
  let body = [];

  if (ex.type === 'choice') {
    body = [h('div', { class: 'instr' }, ex.dir === 'he-en' ? 'איך אומרים באנגלית?' : 'מה הפירוש?'),
      h('div', { class: 'prompt' }, ex.dir === 'en-he' ? [speakBtn(ex.prompt), en(ex.prompt)] : ex.prompt),
      optionList(ex.options, ex.dir === 'he-en', (v) => { setResponse(v); if (ex.dir === 'he-en') say(v); })];
    if (ex.dir === 'en-he') setTimeout(() => say(ex.prompt), 250);
  } else if (ex.type === 'listen') {
    const canHear = ttsAvailable();
    const reveal = h('p', { class: 'muted center', hidden: canHear }, 'אין קול במכשיר? המילה היא: ', en(ex.prompt));
    body = [h('div', { class: 'instr' }, 'הקשיבו ובחרו מה שמעתם'),
      h('button', { class: 'speak-big', 'aria-label': 'השמעה', onclick: () => say(ex.prompt) }, '🔊'),
      canHear ? h('p', { class: 'center' }, h('button', { class: 'btn ghost', style: 'width:auto', onclick: () => say(ex.prompt, 0.55, true) }, '🐢 לאט יותר'), h('button', { class: 'btn ghost', style: 'width:auto', onclick: () => { reveal.hidden = false; } }, 'לא שומע/ת')) : null,
      reveal, optionList(ex.options, true, setResponse)];
    setTimeout(() => say(ex.prompt), 300);
  } else if (ex.type === 'build') {
    const picked = [];
    const line = h('div', { class: 'answer-line', 'aria-label': 'התשובה שלך', role: 'group' });
    const bank = h('div', { class: 'bank', role: 'group', 'aria-label': 'מאגר מילים' });
    const tiles = ex.bank.map((w, idx) => h('button', { class: 'tile', lang: 'en', 'data-word': w, onclick: () => { picked.push(idx); tiles[idx].classList.add('used'); tiles[idx].setAttribute('aria-hidden', 'true'); tiles[idx].tabIndex = -1; say(w); redraw(); } }, w));
    bank.append(...tiles);
    function redraw() {
      line.replaceChildren(...picked.map((idx, pos) => h('button', { class: 'tile', lang: 'en', 'aria-label': `הסרה: ${ex.bank[idx]}`, onclick: () => { picked.splice(pos, 1); tiles[idx].classList.remove('used'); tiles[idx].removeAttribute('aria-hidden'); tiles[idx].tabIndex = 0; redraw(); } }, ex.bank[idx])));
      setResponse(picked.length ? picked.map((i) => ex.bank[i]).join(' ') : null);
    }
    body = [h('div', { class: 'instr' }, 'תרגמו לאנגלית'), h('div', { class: 'prompt' }, ex.prompt), line, bank];
  } else if (ex.type === 'type') {
    const inp = h('input', { class: 'text-in', type: 'text', lang: 'en', dir: 'ltr', autocomplete: 'off', autocapitalize: 'none', spellcheck: 'false', 'aria-label': 'כתבו באנגלית', placeholder: 'Type in English', oninput: (e) => setResponse(e.target.value.trim()), onkeydown: (e) => { if (e.key === 'Enter' && !checkBtn.disabled) submit(); } });
    body = [h('div', { class: 'instr' }, 'כתבו באנגלית'), h('div', { class: 'prompt' }, ex.prompt), inp];
    setTimeout(() => inp.focus(), 50);
  } else if (ex.type === 'match') {
    const left = shuffle(ex.pairs), right = shuffle(ex.pairs);
    let sel = null, matched = 0, misses = 0;
    const grid = h('div', { class: 'match' });
    const mk = (w, side) => h('button', { class: 'chip', 'data-side': side, 'data-en': w.en, lang: side === 'en' ? 'en' : 'he', 'aria-pressed': 'false', onclick: (e) => tap(e.currentTarget, w, side) }, side === 'en' ? w.en : w.he);
    for (let i = 0; i < ex.pairs.length; i++) grid.append(mk(left[i], 'en'), mk(right[i], 'he'));
    function tap(btn, w, side) {
      if (side === 'en') say(w.en);
      if (!sel || sel.side === side) { if (sel) sel.btn.setAttribute('aria-pressed', 'false'); sel = { btn, w, side }; btn.setAttribute('aria-pressed', 'true'); return; }
      if (sel.w.en === w.en) {
        for (const b of [sel.btn, btn]) { b.classList.add('correct'); setTimeout(() => { b.classList.add('gone'); b.disabled = true; }, 250); }
        matched += 1; seeWord(state, w.en, true);
        if (matched === ex.pairs.length) { setResponse(misses <= 1); submit(); }
      } else {
        misses += 1; btn.classList.add('wrong'); const o = sel.btn; o.classList.add('wrong');
        setTimeout(() => { btn.classList.remove('wrong'); o.classList.remove('wrong'); }, 500);
        announce('לא תואם, נסו שוב');
      }
      sel.btn.setAttribute('aria-pressed', 'false'); sel = null;
    }
    body = [h('div', { class: 'instr' }, 'התאימו את הזוגות'), grid];
  }

  function submit() {
    const res = check(ex, response);
    s.answered += 1;
    if (res.ok) s.correct += 1;
    else if (!s.retried.has(s.i)) { s.retried.add(s.queue.length); s.queue.push({ ...ex, options: ex.options && shuffle(ex.options) }); s.mistakes.push(ex); }
    if (ex.word) seeWord(state, ex.word, res.ok);
    persist();
    showFeedback(main, ex, res, () => { s.i += 1; if (s.i >= s.queue.length) finishSession(); else drawExercise(main); });
  }

  const footer = ex.type === 'match' ? null : h('div', { class: 'footer-action' }, checkBtn);
  main.replaceChildren(h('div', { class: 'lesson-top' }, quit, bar), ...body.filter(Boolean), footer || '');
  const ins = main.querySelector('.instr'); if (ins) { ins.tabIndex = -1; ins.focus({ preventScroll: true }); }
}

function speakBtn(text) { return h('button', { class: 'icon-btn', 'aria-label': 'השמעה', onclick: () => say(text) }, '🔊'); }
function optionList(options, isEn, onPick) {
  const wrap = h('div', { class: 'options', role: 'radiogroup', 'aria-label': 'אפשרויות' });
  options.forEach((o, i) => wrap.append(h('button', { class: 'chip', role: 'radio', 'aria-checked': 'false', 'data-value': o, lang: isEn ? 'en' : 'he', dir: isEn ? 'ltr' : 'rtl',
    onclick: (e) => { wrap.querySelectorAll('.chip').forEach((c) => c.setAttribute('aria-checked', 'false')); e.currentTarget.setAttribute('aria-checked', 'true'); onPick(o); } },
    h('span', { class: 'pill', 'aria-hidden': 'true', style: 'padding:2px 9px' }, i + 1), o)));
  return wrap;
}

function showFeedback(main, ex, res, next) {
  main.querySelectorAll('button:not(.icon-btn), input').forEach((b) => { b.disabled = true; });
  const l = session.kind === 'lesson' ? findLesson(session.id) : null;
  const praise = ['מעולה!', 'יפה מאוד!', 'נכון!', 'כל הכבוד!', 'אש! 🔥'];
  const right = ex.type === 'match' ? null : ex.answer;
  const sheet = h('div', { class: `sheet ${res.ok ? 'ok' : 'bad'}`, role: 'alertdialog', 'aria-live': 'assertive', 'aria-label': res.ok ? 'תשובה נכונה' : 'תשובה שגויה' },
    h('div', { class: 'inner stack' },
      h('h2', {}, res.ok ? '✅ ' + praise[Math.floor(Math.random() * praise.length)] : '❌ לא בדיוק'),
      res.typo ? h('p', {}, 'שימו לב לכתיב: ', en(right)) : null,
      !res.ok && right ? h('p', {}, 'התשובה הנכונה: ', h('b', { lang: /[a-z]/i.test(right) ? 'en' : 'he' }, right)) : null,
      !res.ok && ex.type === 'match' ? h('p', {}, 'היו כמה טעויות – נחזור על המילים האלה בתרגול.') : null,
      !res.ok && l?.tip ? h('p', { class: 'tip' }, '💡 ', mixed(l.tip)) : null,
      h('button', { class: `btn ${res.ok ? 'success' : 'danger'}`, onclick: () => { sheet.remove(); next(); } }, 'המשך')));
  document.body.append(sheet);
  if (/[a-z]/i.test(right || '')) say(right);
  sheet.querySelector('.btn').focus();
}

let lastResult = null;
function finishSession() {
  const s = session; s.finished = true;
  const acc = s.answered ? s.correct / s.answered : 1;
  if (s.kind === 'lesson') {
    const r = completeLesson(state, s.id, acc);
    lastResult = { ...r, acc, kind: 'lesson', id: s.id };
  } else {
    store.addXp(state, 5 + s.correct); store.touchStreak(state);
    lastResult = { xp: 5 + s.correct, acc, kind: 'review', streak: state.profile.streak };
  }
  persist();
  go('done');
}

// ---------- S05 lesson complete ----------
function doneScreen() {
  if (!lastResult) { setTimeout(() => go('home')); return h('main'); }
  const r = lastResult;
  const goalHit = (state.profile.xpByDay[store.today()] || 0) >= state.profile.dailyGoalXp;
  const main = h('main', { id: 'main', class: 'stack center' },
    h('div', { class: 'hero', 'aria-hidden': 'true' }, r.acc >= 0.9 ? '🏆' : r.acc >= 0.6 ? '🎉' : '💪'),
    h('h1', {}, r.kind === 'review' ? 'סיימתם תרגול!' : 'סיימתם את השיעור!'),
    h('div', { class: 'stats' },
      h('div', { class: 'card stat' }, h('b', {}, '+' + r.xp), 'נקודות'),
      h('div', { class: 'card stat' }, h('b', {}, Math.round(r.acc * 100) + '%'), 'דיוק'),
      h('div', { class: 'card stat', style: 'color:var(--streak)' }, h('b', {}, '🔥' + r.streak), 'ימי רצף')),
    goalHit ? h('p', { class: 'tip' }, '🎯 עמדתם ביעד היומי!') : null,
    h('div', { class: 'footer-action' }, h('button', { class: 'btn', onclick: async () => { await ads.maybeInterstitial(state); persist(); lastResult = null; go(r.kind === 'review' ? 'practice' : 'home'); } }, 'המשך')));
  return main;
}

// ---------- S06 practice ----------
function practice() {
  const d = due(state);
  const main = h('main', { id: 'main' }, topbar(), h('h1', {}, 'תרגול חוזר'));
  if (!d.length) {
    main.append(h('div', { class: 'empty' }, h('span', { class: 'em', 'aria-hidden': 'true' }, '🌤️'),
      h('p', {}, Object.keys(state.words).length ? 'אין מילים לחזרה כרגע. חזרו מחר!' : 'עוד אין מילים לתרגל. סיימו שיעור ראשון והן יופיעו כאן.'),
      h('a', { class: 'btn', href: '#/home' }, 'לשיעור הבא')));
  } else {
    main.append(h('div', { class: 'card stack' }, h('p', {}, `${d.length} מילים מחכות לחזרה. חזרה בזמן הנכון עוזרת לזכור לטווח ארוך.`),
      h('button', { class: 'btn', onclick: () => { startSession('review', 'review', buildReview(d)); go('lesson/review'); } }, 'התחילו תרגול')));
  }
  const p = state.profile;
  main.append(h('div', { class: 'card stack', style: 'margin-top:16px' }, h('h2', {}, '🧊 הקפאת רצף'),
    h('p', { class: 'muted' }, `יש לכם ${p.freezes} הקפאות. הקפאה שומרת על הרצף אם פספסתם יום אחד. מקבלים אחת חינם כל שבוע.`),
    ads.rewardedAvailable() ? h('button', { class: 'btn secondary', onclick: async () => {
      if (p.freezes >= 3) return toast('אפשר להחזיק עד 3 הקפאות');
      const ok = await ads.showRewarded();
      if (ok) { p.freezes += 1; persist(); toast('קיבלתם הקפאה נוספת 🧊'); render(); } else toast('הסרטון לא זמין כרגע');
    } }, '🎬 צפו בפרסומת קצרה וקבלו הקפאה') : null));
  return main;
}

// ---------- S07 words ----------
function words() {
  const known = allWords().filter((w) => state.words[w.en]);
  const main = h('main', { id: 'main' }, topbar(), h('h1', {}, 'המילים שלי'), h('p', { class: 'muted' }, `${known.length} מתוך ${allWords().length} מילים`));
  if (!known.length) { main.append(h('div', { class: 'empty' }, h('span', { class: 'em', 'aria-hidden': 'true' }, '📖'), h('p', {}, 'המילים שתלמדו יופיעו כאן, עם השמעה.'))); return main; }
  const list = h('ul', { class: 'list' });
  const search = h('input', { class: 'search', type: 'search', placeholder: 'חיפוש בעברית או באנגלית', 'aria-label': 'חיפוש מילה', oninput: (e) => draw(e.target.value) });
  function draw(q = '') {
    const qq = q.trim().toLowerCase();
    const rows = known.filter((w) => !qq || w.en.toLowerCase().includes(qq) || w.he.includes(qq));
    list.replaceChildren(...(rows.length ? rows.map((w) => {
      const st = state.words[w.en];
      return h('li', {}, speakBtn(w.en), h('div', { class: 'spacer' }, h('b', {}, en(w.en)), h('br'), h('span', { class: 'muted' }, w.he)),
        h('span', { class: 'pill', 'aria-label': `רמת שליטה ${st.box} מתוך 5` }, '●'.repeat(st.box) + '○'.repeat(5 - st.box)));
    }) : [h('li', { class: 'muted' }, 'לא נמצאו מילים')]));
  }
  draw();
  main.append(search, list);
  return main;
}

// ---------- S08 profile ----------
function profile() {
  const p = state.profile;
  const doneN = allLessons().filter((l) => isDone(state, l.id)).length;
  const days = [...Array(7)].map((_, i) => store.addDays(store.today(), i - 6));
  const max = Math.max(p.dailyGoalXp, ...days.map((d) => p.xpByDay[d] || 0));
  const names = ['א׳', 'ב׳', 'ג׳', 'ד׳', 'ה׳', 'ו׳', 'ש׳'];
  const chart = h('div', { class: 'chart', role: 'img', 'aria-label': 'נקודות ב־7 הימים האחרונים: ' + days.map((d) => `${p.xpByDay[d] || 0}`).join(', ') },
    days.map((d) => { const v = p.xpByDay[d] || 0; const [y, m, dd] = d.split('-').map(Number); const dow = new Date(y, m - 1, dd).getDay();
      return h('div', { class: 'col' }, h('small', {}, v), h('div', { class: 'b', style: `height:${Math.round((v / max) * 100)}%` }), h('small', {}, names[dow])); }));
  return h('main', { id: 'main', class: 'stack' }, topbar(), h('h1', {}, 'ההתקדמות שלי'),
    h('div', { class: 'stats' },
      h('div', { class: 'card stat', style: 'color:var(--streak)' }, h('b', {}, '🔥' + store.liveStreak(state)), 'רצף'),
      h('div', { class: 'card stat' }, h('b', {}, p.xpTotal), 'נקודות'),
      h('div', { class: 'card stat' }, h('b', {}, `${doneN}/${allLessons().length}`), 'שיעורים')),
    h('div', { class: 'card' }, h('h2', {}, 'השבוע'), chart),
    h('div', { class: 'card' }, h('p', { style: 'margin:0' }, `🏅 הרצף הכי ארוך: ${p.bestStreak || 0} ימים · 🧊 הקפאות: ${p.freezes}`)),
    h('a', { class: 'btn secondary', href: '#/settings' }, '⚙️ הגדרות'));
}

// ---------- S09 settings ----------
function settings() {
  const st = state.settings, p = state.profile;
  const sw = (label, key, obj = st) => h('div', { class: 'setting' }, h('span', { class: 'spacer', id: 'l-' + key }, label),
    h('button', { class: 'switch', role: 'switch', 'aria-checked': String(!!obj[key]), 'aria-labelledby': 'l-' + key, onclick: (e) => { obj[key] = !obj[key]; e.currentTarget.setAttribute('aria-checked', String(obj[key])); persist(); applySettings(); } }));
  const sel = (label, id, value, opts, onchange) => h('div', { class: 'setting' }, h('label', { class: 'spacer', for: id }, label),
    h('select', { class: 'sel', id, onchange: (e) => { onchange(e.target.value); persist(); applySettings(); } }, opts.map(([v, t]) => h('option', { value: v, selected: String(v) === String(value) }, t))));
  const privacyBtn = h('button', { class: 'btn secondary', onclick: async () => { const ok = await ads.showPrivacyOptions(); if (!ok) toast('בגרסת הדפדפן אין פרסומות מותאמות. באנדרואיד ייפתח כאן טופס הבחירה של Google.'); } }, '🔐 בחירות פרטיות ופרסום');
  return h('main', { id: 'main', class: 'stack' },
    h('div', { class: 'row' }, h('a', { class: 'icon-btn', href: '#/profile', 'aria-label': 'חזרה' }, '→'), h('h1', { style: 'margin:0' }, 'הגדרות')),
    h('section', { class: 'card' }, h('h2', {}, 'לימוד'),
      sw('🔊 השמעת קול', 'sound'),
      h('div', { class: 'setting' }, h('span', { class: 'spacer' }, 'לא שומעים כלום?'),
        h('button', { class: 'btn secondary', style: 'width:auto;min-height:44px', onclick: async () => {
          const st = await say('Hello! The sound is working.', state.settings.ttsRate, true);
          if (st === 'ok') toast('אם לא שמעתם – הגבירו את עוצמת המדיה בטלפון 🔊');
        } }, '🔈 בדיקת קול')),
      sel('מהירות דיבור', 'rate', st.ttsRate, [[0.6, 'איטית'], [0.85, 'רגילה'], [1, 'מהירה']], (v) => (st.ttsRate = Number(v))),
      sel('🎯 יעד יומי', 'goal', p.dailyGoalXp, [[10, 'קליל – 10'], [20, 'רגיל – 20'], [40, 'רציני – 40'], [60, 'אינטנסיבי – 60']], (v) => (p.dailyGoalXp = Number(v)))),
    h('section', { class: 'card' }, h('h2', {}, 'תצוגה ונגישות'),
      sel('ערכת צבע', 'theme', st.theme, [['auto', 'לפי המכשיר'], ['light', 'בהירה'], ['dark', 'כהה']], (v) => (st.theme = v)),
      sel('גודל טקסט', 'fs', st.fontScale, [[1, 'רגיל'], [1.15, 'גדול'], [1.3, 'גדול מאוד']], (v) => (st.fontScale = Number(v))),
      sw('הפחתת אנימציות', 'reducedMotion')),
    h('section', { class: 'card stack' }, h('h2', {}, 'פרטיות ומידע'), privacyBtn,
      h('p', {}, h('a', { href: 'legal/privacy.html' }, 'מדיניות פרטיות'), ' · ', h('a', { href: 'legal/terms.html' }, 'תנאי שימוש'), ' · ', h('a', { href: 'legal/accessibility.html' }, 'הצהרת נגישות')),
      h('p', { class: 'muted' }, 'יצירת קשר: ', h('a', { href: 'mailto:' + APP.contactEmail }, APP.contactEmail)),
      h('button', { class: 'btn danger', onclick: () => { if (confirm('למחוק את כל ההתקדמות והנתונים מהמכשיר? אי אפשר לבטל.')) { state = store.reset(); session = null; applySettings(); toast('כל הנתונים נמחקו'); go('welcome'); } } }, '🗑️ מחיקת כל הנתונים שלי')),
    h('p', { class: 'muted center' }, en(`Safa v${APP.version} · build ${typeof __SAFA_BUILD__ !== 'undefined' ? __SAFA_BUILD__ : 'dev'}`)));
}

// ---------- boot ----------
applySettings();
warmUp();
if (state.profile.onboarded) ads.initAds(state).then((ok) => { persist(); if (ok && ['home', 'practice', 'words', 'profile', 'settings'].includes(current)) ads.showBanner(); });
render();
window.__safa = { state: () => state, store, go };
