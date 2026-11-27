import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { Store } from '../src/store.js';
import { TaskQueue } from '../src/queue.js';
import { createServer } from '../src/server.js';

let server;
let base;
let queue;
const token = 'test-token';
const auth = { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' };

before(async () => {
  queue = new TaskQueue(new Store(null));
  server = createServer(queue, { token });
  await new Promise((r) => server.listen(0, '127.0.0.1', r));
  base = `http://127.0.0.1:${server.address().port}`;
});

after(() => {
  queue.stop();
  server.close();
});

test('health is public, API needs the token', async () => {
  assert.equal((await fetch(`${base}/health`)).status, 200);
  const r = await fetch(`${base}/api/v1/tasks`);
  assert.equal(r.status, 401);
  assert.match((await r.json()).error, /אסימון/);
  assert.equal((await fetch(`${base}/api/v1/tasks`, { headers: { Authorization: 'Bearer wrong' } })).status, 401);
});

test('admin page is served in Hebrew RTL', async () => {
  const html = await (await fetch(`${base}/`)).text();
  assert.match(html, /<html lang="he" dir="rtl">/);
});

test('full round trip: create → process → sync → result', async () => {
  const created = await fetch(`${base}/api/v1/tasks`, {
    method: 'POST', headers: auth,
    body: JSON.stringify({ type: 'reminder', deviceId: 'dev1', payload: { text: 'לשתות מים' } }),
  });
  assert.equal(created.status, 201);
  const { task } = await created.json();
  await queue.tick();

  const wait = await (await fetch(`${base}/api/v1/wait?deviceId=dev1&timeout=1`, { headers: auth })).json();
  assert.equal(wait.changed, true);

  const sync = async (body) => (await fetch(`${base}/api/v1/sync`, { method: 'POST', headers: auth, body: JSON.stringify(body) })).json();
  const r1 = await sync({ deviceId: 'dev1', status: { battery: 80, network: 'wifi' } });
  assert.equal(r1.tasks[0].id, task.id);
  assert.equal(r1.tasks[0].result.text, 'לשתות מים');
  await sync({ deviceId: 'dev1', received: [task.id], results: [{ taskId: task.id, status: 'done', note: 'הוצגה התראה' }] });
  const got = await (await fetch(`${base}/api/v1/tasks/${task.id}`, { headers: auth })).json();
  assert.equal(got.task.status, 'done');
  const devices = await (await fetch(`${base}/api/v1/devices`, { headers: auth })).json();
  assert.equal(devices.devices[0].status.battery, 80);
});

test('bad input gives Hebrew 400 errors', async () => {
  const bad = await fetch(`${base}/api/v1/tasks`, { method: 'POST', headers: auth, body: '{oops' });
  assert.equal(bad.status, 400);
  assert.equal((await bad.json()).error, 'JSON לא תקין');
  const badType = await fetch(`${base}/api/v1/tasks`, { method: 'POST', headers: auth, body: JSON.stringify({ type: 'x' }) });
  assert.equal(badType.status, 400);
  const noDevice = await fetch(`${base}/api/v1/sync`, { method: 'POST', headers: auth, body: '{}' });
  assert.equal(noDevice.status, 400);
  assert.equal((await fetch(`${base}/nope`)).status, 404);
});

test('fetch_url runs on the server against a real HTTP endpoint', async () => {
  const t = queue.createTask({ type: 'fetch_url', payload: { url: `${base}/`, contains: 'תור משימות' } });
  await queue.tick();
  const res = queue.getTask(t.id).result;
  assert.equal(res.httpStatus, 200);
  assert.equal(res.found, true);
});
