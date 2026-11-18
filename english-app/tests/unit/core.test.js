import { test } from 'node:test';
import assert from 'node:assert/strict';
import { UNITS, allLessons, allWords } from '../../src/data/course.js';
import { fresh, touchStreak, liveStreak, addDays, dayDiff, isoWeek } from '../../src/store.js';
import { buildLesson, check, closeEnough, normalize } from '../../src/lesson-engine.js';
import { seeWord, due } from '../../src/srs.js';
import { isUnlocked, completeLesson, nextLesson } from '../../src/progress.js';

test('content is well formed', () => {
  const ids = new Set();
  for (const l of allLessons()) {
    assert.ok(!ids.has(l.id), 'duplicate id ' + l.id); ids.add(l.id);
    if (l.kind === 'letters') { assert.ok(l.letters.length >= 5, l.id + ' needs 5 letters'); continue; }
    assert.ok(l.words.length >= 6, l.id + ' needs 6 words');
    assert.ok(l.sentences.length >= 2, l.id + ' needs 2 sentences');
    for (const [en, he] of [...l.words, ...l.sentences]) { assert.ok(en && he, l.id); assert.match(he, /[֐-׿]/, 'hebrew in ' + l.id); }
  }
  assert.ok(UNITS.length >= 9); assert.ok(allWords().length > 150);
});

test('every lesson builds valid, answerable exercises', () => {
  for (const l of allLessons()) for (let r = 0; r < 5; r++) {
    for (const ex of buildLesson(l)) {
      if (ex.options) { assert.ok(ex.options.includes(ex.answer), l.id); assert.equal(new Set(ex.options).size, ex.options.length, 'dup option ' + l.id); }
      if (ex.type === 'build') assert.ok(check(ex, ex.answer.replace(/[.,!?]/g, '')).ok);
      if (ex.type === 'build') for (const t of normalize(ex.answer).split(' ')) assert.ok(ex.bank.map(normalize).includes(t), `${l.id} bank missing ${t}`);
      if (ex.type === 'match') assert.equal(ex.pairs.length, 4);
      if (ex.type === 'intro') assert.ok(ex.item.L && ex.item.name && ex.item.word);
    }
  }
});

test('typed answers accept a small typo but not a wrong word', () => {
  assert.equal(closeEnough('Good Morning!', 'good morning'), 'exact');
  assert.equal(closeEnough('morming', 'morning'), 'typo');
  assert.equal(closeEnough('cat', 'car'), false);
  assert.equal(closeEnough('bye', 'yes'), false);
});

test('streak: consecutive days, freeze covers one missed day, reset after', () => {
  const s = fresh();
  touchStreak(s, '2026-11-16'); assert.equal(s.profile.streak, 1);
  touchStreak(s, '2026-11-16'); assert.equal(s.profile.streak, 1);
  touchStreak(s, '2026-11-17'); assert.equal(s.profile.streak, 2);
  touchStreak(s, '2026-11-19'); assert.equal(s.profile.streak, 3); assert.equal(s.profile.freezes, 0);
  assert.equal(liveStreak(s, '2026-11-22'), 0);
  touchStreak(s, '2026-11-22'); assert.equal(s.profile.streak, 1);
});

test('dates across month/year and DST boundaries', () => {
  assert.equal(addDays('2026-12-31', 1), '2027-01-01');
  assert.equal(dayDiff('2026-03-26', '2026-03-28'), 2); // Israel DST change
  assert.equal(isoWeek('2026-11-16'), '2026-W47');
});

test('spaced repetition boxes', () => {
  const s = fresh();
  seeWord(s, 'hello', true, '2026-11-16'); assert.equal(s.words.hello.box, 2); assert.equal(s.words.hello.next, '2026-11-17');
  seeWord(s, 'bye', false, '2026-11-16'); assert.deepEqual(due(s, '2026-11-16'), ['bye']);
  assert.deepEqual(due(s, '2026-11-17').sort(), ['bye', 'hello']);
});

test('lessons unlock in order and give XP', () => {
  const s = fresh(); const [a, b] = allLessons();
  assert.ok(isUnlocked(s, a.id)); assert.ok(!isUnlocked(s, b.id));
  const r = completeLesson(s, a.id, 1); assert.equal(r.xp, 20); assert.ok(isUnlocked(s, b.id));
  assert.equal(nextLesson(s).id, b.id);
  assert.equal(completeLesson(s, a.id, 1).xp, 10); // replay gives less
});

test('alphabet: all 26 letters taught in order, every letter lesson starts with intro cards', () => {
  const taught = allLessons().filter((l) => l.kind === 'letters' && l.id.startsWith('abc') && l.id !== 'abc6').flatMap((l) => l.letters.map((x) => x.L));
  assert.equal(taught.join(''), 'ABCDEFGHIJKLMNOPQRSTUVWXYZ');
  for (const l of allLessons().filter((x) => x.kind === 'letters')) {
    const ex = buildLesson(l);
    assert.equal(ex[0].type, 'intro');
    assert.equal(ex.filter((e) => e.type === 'intro').length, l.letters.length);
    const sp = ex.find((e) => e.type === 'build');
    if (sp) assert.ok(check(sp, sp.answer).ok);
  }
});
