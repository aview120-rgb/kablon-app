// Builds exercises from lesson data. Pure functions, easy to unit-test.
import { allWords, ALPHABET } from './data/course.js';

export function normalize(s) {
  return String(s).toLowerCase().replace(/[’']/g, "'").replace(/[.,!?;:"״׳()]/g, ' ').replace(/\s+/g, ' ').trim();
}
export function tokens(sentence) {
  return normalize(sentence).split(' ').filter(Boolean);
}
// Accept small typos in typed answers (1 edit for words > 4 letters).
export function closeEnough(input, answer) {
  const a = normalize(input), b = normalize(answer);
  if (a === b) return 'exact';
  if (b.length > 4 && lev(a, b) <= 1) return 'typo';
  return false;
}
function lev(a, b) {
  const dp = Array.from({ length: a.length + 1 }, (_, i) => [i]);
  for (let j = 1; j <= b.length; j++) dp[0][j] = j;
  for (let i = 1; i <= a.length; i++) for (let j = 1; j <= b.length; j++)
    dp[i][j] = Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1, dp[i - 1][j - 1] + (a[i - 1] === b[j - 1] ? 0 : 1));
  return dp[a.length][b.length];
}

export function shuffle(arr, rnd = Math.random) {
  const a = [...arr];
  for (let i = a.length - 1; i > 0; i--) { const j = Math.floor(rnd() * (i + 1)); [a[i], a[j]] = [a[j], a[i]]; }
  return a;
}
const sample = (arr, n, rnd) => shuffle(arr, rnd).slice(0, n);

function distractors(lesson, correct, field, n, rnd) {
  const own = lesson.words.map(([en, he]) => ({ en, he }));
  const pool = [...own, ...sample(allWords(), 30, rnd)];
  const out = [];
  for (const w of shuffle(pool, rnd)) {
    const v = w[field];
    if (v !== correct && !out.includes(v)) out.push(v);
    if (out.length >= n) break;
  }
  return out;
}

// Letter lessons: learn each letter (intro card), then practise it, step by step.
export function buildLetterLesson(lesson, rnd = Math.random) {
  const items = lesson.letters;
  const pool = lesson.letters[0].L.length > 1 ? items : ALPHABET; // digraphs only mix with each other
  const others = (it, n, key) => shuffle(pool.filter((x) => x[key] !== it[key] && !items.includes(x)), rnd).slice(0, n);
  const opts = (it, key) => shuffle([it[key], ...shuffle(items.filter((x) => x !== it), rnd).slice(0, 2).map((x) => x[key]), ...others(it, 1, key).map((x) => x[key])], rnd)
    .filter((v, i, a) => a.indexOf(v) === i);
  const intro = (it) => ({ type: 'intro', item: it, noScore: true });
  const listen = (it) => ({ type: 'listen', prompt: it.say, show: it.L, answer: it.L, options: opts(it, 'L'), letter: true });
  const byName = (it) => ({ type: 'choice', dir: 'he-en', prompt: `איזו אות היא "${it.name}"?`, answer: it.L, options: opts(it, 'L'), letter: true });
  const starts = (it) => ({ type: 'choice', dir: 'he-en', prompt: `באיזו אות מתחילה המילה ${it.word} (${it.he})?`, speakEn: it.word, answer: it.L, options: opts(it, 'L'), letter: true });
  const spell = (it) => {
    const letters = it.word.split('');
    const extra = shuffle('abcdefghijklmnoprstuvwy'.split('').filter((c) => !letters.includes(c)), rnd).slice(0, 2);
    return { type: 'build', prompt: `איך כותבים "${it.he}" באנגלית?`, speakEn: it.word, answer: letters.join(' '), display: it.word, bank: shuffle([...letters, ...extra], rnd), word: it.word };
  };
  const ex = [];
  const seen = [];
  items.forEach((it, i) => {
    ex.push(intro(it)); seen.push(it);
    if (i % 2 === 1) ex.push(rnd() < 0.5 ? listen(seen[seen.length - 1 - Math.floor(rnd() * 2)]) : byName(seen[seen.length - 1 - Math.floor(rnd() * 2)]));
  });
  const mix = shuffle(items, rnd);
  if (items[0].L !== items[0].l) ex.push({ type: 'match', pairs: shuffle(items, rnd).slice(0, 4).map((x) => ({ en: x.L, he: x.l })), noSrs: true, caseMatch: true });
  else ex.push({ type: 'match', pairs: shuffle(items, rnd).slice(0, 4).map((x) => ({ en: x.L, he: x.name })), noSrs: true });
  ex.push(byName(mix[0]), listen(mix[1]), starts(mix[2]), starts(mix[3 % mix.length]));
  const shortWord = mix.find((x) => x.word.length <= 5 && !x.word.includes(' '));
  if (shortWord) ex.push(spell(shortWord));
  ex.push({ type: 'type', prompt: `כתבו באנגלית את האות "${mix[4 % mix.length].name}"`, answer: mix[4 % mix.length].l, letter: true });
  return ex;
}

export function buildLesson(lesson, rnd = Math.random) {
  if (lesson.kind === 'letters') return buildLetterLesson(lesson, rnd);
  const words = lesson.words.map(([en, he]) => ({ en, he }));
  const sents = lesson.sentences.map(([en, he]) => ({ en, he }));
  const ws = shuffle(words, rnd);
  const ex = [];
  const choiceHeEn = (w) => ({ type: 'choice', dir: 'he-en', prompt: w.he, answer: w.en, word: w.en,
    options: shuffle([w.en, ...distractors(lesson, w.en, 'en', 3, rnd)], rnd) });
  const choiceEnHe = (w) => ({ type: 'choice', dir: 'en-he', prompt: w.en, answer: w.he, word: w.en,
    options: shuffle([w.he, ...distractors(lesson, w.he, 'he', 3, rnd)], rnd) });
  const listen = (w) => ({ type: 'listen', prompt: w.en, answer: w.en, word: w.en, he: w.he,
    options: shuffle([w.en, ...distractors(lesson, w.en, 'en', 3, rnd)], rnd) });
  const build = (s) => {
    const t = s.en.replace(/[.,!?;:]/g, ' ').split(/\s+/).filter(Boolean);
    const low = t.map(normalize);
    const extra = distractors(lesson, '', 'en', 8, rnd).filter((x) => !x.includes(' ') && !low.includes(normalize(x))).slice(0, 3);
    return { type: 'build', prompt: s.he, answer: s.en, bank: shuffle([...t, ...extra], rnd) };
  };
  const type = (w) => ({ type: 'type', prompt: w.he, answer: w.en, word: w.en });
  const match = () => ({ type: 'match', pairs: sample(words, 4, rnd) });

  ex.push(choiceHeEn(ws[0]));
  ex.push(listen(ws[1]));
  ex.push(choiceEnHe(ws[2]));
  ex.push(build(sents[0]));
  ex.push(match());
  ex.push(choiceHeEn(ws[3]));
  ex.push(build(sents[1]));
  ex.push(type(ws[4]));
  if (sents[2]) ex.push(build(sents[2]));
  ex.push(listen(ws[5] || ws[0]));
  return ex;
}

// Review session from due words (practice tab).
export function buildReview(wordList, rnd = Math.random) {
  const all = allWords();
  return wordList.slice(0, 8).map((en, i) => {
    const w = all.find((x) => x.en === en) || { en, he: en };
    const fake = { words: [[w.en, w.he]] };
    if (i % 3 === 1) return { type: 'listen', prompt: w.en, answer: w.en, word: w.en, he: w.he, options: shuffle([w.en, ...distractors(fake, w.en, 'en', 3, rnd)], rnd) };
    if (i % 3 === 2) return { type: 'type', prompt: w.he, answer: w.en, word: w.en };
    return { type: 'choice', dir: 'he-en', prompt: w.he, answer: w.en, word: w.en, options: shuffle([w.en, ...distractors(fake, w.en, 'en', 3, rnd)], rnd) };
  });
}

export function check(ex, response) {
  switch (ex.type) {
    case 'choice': case 'listen': return { ok: response === ex.answer };
    case 'build': return { ok: normalize(response) === normalize(ex.answer) };
    case 'type': { const r = closeEnough(response, ex.answer); return { ok: !!r, typo: r === 'typo' }; }
    case 'match': return { ok: response === true };
    case 'intro': return { ok: true };
    default: return { ok: false };
  }
}
