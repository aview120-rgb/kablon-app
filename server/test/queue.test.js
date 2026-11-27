import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { Store } from '../src/store.js';
import { TaskQueue, backoffMs } from '../src/queue.js';
import { renderTemplate } from '../src/processors.js';

function makeQueue(start = Date.parse('2026-11-27T08:00:00Z'), extra = {}) {
  let now = start;
  const q = new TaskQueue(new Store(null), { now: () => now, ...extra });
  return { q, advance: (ms) => { now += ms; }, now: () => now };
}

const msg = (over = {}) => ({
  type: 'send_message',
  payload: { channel: 'WHATSAPP', recipient: '+972501234567', recipientLabel: 'דנה', text: 'בוקר טוב! היום {{weekday}}' },
  ...over,
});

test('message task is prepared ahead of time and delivered once', async () => {
  const { q, now } = makeQueue();
  const t = q.createTask(msg({ runAt: now() + 3600_000, deviceId: 'phone1' }));
  assert.equal(t.status, 'queued');
  assert.equal(await q.tick(), 1);
  assert.equal(q.getTask(t.id).status, 'ready');
  assert.match(q.getTask(t.id).result.text, /^בוקר טוב! היום יום (ראשון|שני|שלישי|רביעי|חמישי|שישי|שבת)$/);

  const first = q.sync({ deviceId: 'phone1' });
  assert.equal(first.tasks.length, 1);
  assert.equal(first.tasks[0].result.autoSend, true);
  // A lost reply: the same task comes again until the phone confirms it.
  assert.equal(q.sync({ deviceId: 'phone1' }).tasks.length, 1);
  const second = q.sync({ deviceId: 'phone1', received: [t.id] });
  assert.equal(second.tasks.length, 0);
  assert.equal(q.getTask(t.id).status, 'delivered');
});

test('tasks for another device are never delivered', async () => {
  const { q, now } = makeQueue();
  q.createTask(msg({ runAt: now(), deviceId: 'phoneA' }));
  await q.tick();
  assert.equal(q.sync({ deviceId: 'phoneB' }).tasks.length, 0);
  assert.equal(q.sync({ deviceId: 'phoneA' }).tasks.length, 1);
});

test('offline results are applied once and duplicates are ignored', async () => {
  const { q, now } = makeQueue();
  const t = q.createTask(msg({ runAt: now(), deviceId: 'p' }));
  await q.tick();
  q.sync({ deviceId: 'p', received: [t.id] });
  const r1 = q.sync({ deviceId: 'p', results: [{ taskId: t.id, status: 'done', note: 'נשלח', at: now() }] });
  assert.deepEqual(r1.acceptedResults, [t.id]);
  assert.equal(q.getTask(t.id).status, 'done');
  q.sync({ deviceId: 'p', results: [{ taskId: t.id, status: 'failed', note: 'כפול' }] });
  assert.equal(q.getTask(t.id).status, 'done');
});

test('uploads are deduplicated by clientId and phone-created tasks are queued', () => {
  const { q, now } = makeQueue();
  const up = [
    { clientId: 'u1', kind: 'inbox', at: now(), data: { sender: 'אמא', text: 'מתי אתה מגיע?' } },
    { clientId: 'u2', kind: 'task', data: { type: 'reminder', payload: { text: 'לקנות חלב' }, runAt: now() + 60_000 } },
  ];
  const r = q.sync({ deviceId: 'p', uploads: up });
  assert.deepEqual(r.acceptedUploads, ['u1', 'u2']);
  q.sync({ deviceId: 'p', uploads: up });
  assert.equal(q.data.uploads.p.length, 1);
  assert.equal(q.listTasks({ deviceId: 'p' }).length, 1);
});

test('digest summarizes uploaded inbox on the server', async () => {
  const { q, now } = makeQueue();
  q.sync({
    deviceId: 'p',
    uploads: [
      { clientId: 'a', kind: 'inbox', at: now() - 1000, data: { app: 'WhatsApp', sender: 'דנה', text: 'שילמתי ₪120' } },
      { clientId: 'b', kind: 'inbox', at: now() - 500, data: { app: 'WhatsApp', sender: 'דנה', text: 'מגיעה?' } },
    ],
  });
  const t = q.createTask({ type: 'digest', deviceId: 'p', payload: { hours: 24 } });
  await q.tick();
  const res = q.getTask(t.id).result;
  assert.equal(res.count, 2);
  assert.match(res.text, /דנה \(WhatsApp\): 2 הודעות, 1 שאלות, סכומים: ₪120/);
});

test('failed processing retries with backoff and then gives up', async () => {
  let calls = 0;
  const failingFetch = async () => { calls++; throw new Error('אין חיבור'); };
  const { q, advance } = makeQueue(undefined, { fetch: failingFetch });
  const t = q.createTask({ type: 'fetch_url', payload: { url: 'https://example.invalid' }, maxAttempts: 3 });
  await q.tick();
  assert.equal(q.getTask(t.id).status, 'queued');
  await q.tick(); // too early, backoff not over
  assert.equal(calls, 1);
  advance(backoffMs(1));
  await q.tick();
  advance(backoffMs(2));
  await q.tick();
  assert.equal(calls, 3);
  assert.equal(q.getTask(t.id).status, 'error');
});

test('validation errors are final immediately', async () => {
  const { q } = makeQueue();
  const t = q.createTask({ type: 'send_message', payload: { recipient: 'a,b', text: 'x' } });
  await q.tick();
  assert.equal(q.getTask(t.id).status, 'error');
  assert.match(q.getTask(t.id).error, /נמען אחד/);
  assert.throws(() => q.createTask({ type: 'nope' }), /לא נתמך/);
});

test('recurring tasks create the next occurrence and stop at count', async () => {
  const { q, now, advance } = makeQueue();
  q.createTask({ type: 'reminder', payload: { text: 'פעם {{occurrence}}' }, runAt: now(), leadMs: 0, repeat: { everyMinutes: 60, count: 2 } });
  await q.tick();
  assert.equal(q.listTasks().length, 2);
  advance(3600_000);
  await q.tick();
  const all = q.listTasks().sort((a, b) => a.occurrence - b.occurrence);
  assert.equal(all.length, 2);
  assert.deepEqual(all.map((t) => t.result.text), ['פעם 1', 'פעם 2']);
});

test('repeat without a count keeps going', async () => {
  const { q, now, advance } = makeQueue();
  q.createTask({ type: 'reminder', payload: { text: 'מים' }, runAt: now(), leadMs: 0, repeat: { everyMinutes: 30 } });
  await q.tick();
  advance(30 * 60_000);
  await q.tick();
  const all = q.listTasks();
  assert.equal(all.length, 3);
  assert.equal(all.filter((t) => t.status === 'ready').length, 2);
  assert.equal(all.filter((t) => t.status === 'error').length, 0);
});

test('cancelling a delivered task tells the phone once', async () => {
  const { q, now } = makeQueue();
  const t = q.createTask(msg({ runAt: now(), deviceId: 'p' }));
  await q.tick();
  q.sync({ deviceId: 'p', received: [t.id] });
  q.cancelTask(t.id);
  assert.deepEqual(q.sync({ deviceId: 'p' }).cancelled, [t.id]);
  q.sync({ deviceId: 'p', received: [t.id] });
  assert.deepEqual(q.sync({ deviceId: 'p' }).cancelled, []);
});

test('long poll wakes up when a task becomes ready', async () => {
  const { q, now } = makeQueue();
  const waiting = q.waitForChanges('p', 5000);
  q.createTask(msg({ runAt: now(), deviceId: 'p' }));
  await q.tick();
  assert.equal(await waiting, true);
  assert.equal(await makeQueue().q.waitForChanges('p', 10), false);
});

test('store persists atomically and recovers from a corrupt file', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'kablon-'));
  const file = path.join(dir, 'db.json');
  const s1 = new Store(file);
  s1.data.tasks.x = { id: 'x' };
  s1.save();
  s1.flush();
  s1.data.tasks.y = { id: 'y' };
  s1.save();
  s1.flush(); // .bak now holds {x}
  fs.writeFileSync(file, '{not json');
  const s2 = new Store(file);
  assert.ok(s2.data.tasks.x);
  fs.rmSync(dir, { recursive: true, force: true });
});

test('template rendering in Israel time', () => {
  const at = Date.parse('2026-11-27T08:05:00Z'); // 10:05 in Jerusalem, Friday
  assert.equal(renderTemplate('{{date}} {{time}} {{weekday}} {{vars.name}} {{unknown}}', { at, vars: { name: 'דנה' } }),
    '27/11/2026 10:05 יום שישי דנה {{unknown}}');
});
