// Kosher-Til management server (Cloudflare Worker + D1).
//
// Device endpoints are read-mostly and unauthenticated (the device only reads
// its policy and may file an unlock request). All STATE CHANGES go through the
// authenticated admin API, so a device can never release itself.

import {
  hashPassword, verifyPassword, createSession, sessionUser,
  destroySession, sessionCookie, clearCookie,
} from './auth.js';
import { loginPage, setupPage, panelPage } from './pages.js';

// Schema is applied automatically on first request so no manual migration
// step is needed (works with the one-click "Deploy to Cloudflare" flow).
const SCHEMA = [
  `CREATE TABLE IF NOT EXISTS managers (username TEXT PRIMARY KEY, salt TEXT NOT NULL, hash TEXT NOT NULL, created_at INTEGER NOT NULL)`,
  `CREATE TABLE IF NOT EXISTS sessions (token TEXT PRIMARY KEY, username TEXT NOT NULL, expires_at INTEGER NOT NULL)`,
  `CREATE TABLE IF NOT EXISTS devices (id TEXT PRIMARY KEY, label TEXT DEFAULT '', enabled INTEGER NOT NULL DEFAULT 1, unlock_until INTEGER NOT NULL DEFAULT 0, manager_contact TEXT DEFAULT '', policy_version INTEGER NOT NULL DEFAULT 1, created_at INTEGER NOT NULL, last_seen INTEGER NOT NULL DEFAULT 0)`,
  `CREATE TABLE IF NOT EXISTS unlock_requests (id INTEGER PRIMARY KEY AUTOINCREMENT, device_id TEXT NOT NULL, reason TEXT DEFAULT '', ts INTEGER NOT NULL, status TEXT NOT NULL DEFAULT 'open')`,
  `CREATE INDEX IF NOT EXISTS idx_requests_status ON unlock_requests(status)`,
];

let schemaReady = false;
async function ensureSchema(env) {
  if (schemaReady) return;
  await env.DB.batch(SCHEMA.map((s) => env.DB.prepare(s)));
  schemaReady = true;
}

const json = (obj, status = 200, headers = {}) =>
  new Response(JSON.stringify(obj), {
    status,
    headers: { 'Content-Type': 'application/json; charset=utf-8', ...headers },
  });

const html = (body, headers = {}) =>
  new Response(body, { headers: { 'Content-Type': 'text/html; charset=utf-8', ...headers } });

const redirect = (to, headers = {}) =>
  new Response(null, { status: 302, headers: { Location: to, ...headers } });

async function managerCount(env) {
  const row = await env.DB.prepare('SELECT COUNT(*) AS n FROM managers').first();
  return row ? row.n : 0;
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const path = url.pathname;
    const method = request.method;

    try {
      await ensureSchema(env);

      // ---- Device API ----------------------------------------------------
      let m = path.match(/^\/api\/device\/([^/]+)\/policy$/);
      if (m && method === 'GET') return devicePolicy(env, decodeURIComponent(m[1]));

      m = path.match(/^\/api\/device\/([^/]+)\/unlock-request$/);
      if (m && method === 'POST') return deviceUnlockRequest(env, decodeURIComponent(m[1]), request);

      // ---- Admin auth ----------------------------------------------------
      if (path === '/api/admin/setup' && method === 'POST') return adminSetup(env, request);
      if (path === '/api/admin/login' && method === 'POST') return adminLogin(env, request);
      if (path === '/api/admin/logout' && method === 'POST') return adminLogout(env, request);

      // ---- Admin API (session required) ----------------------------------
      if (path.startsWith('/api/admin/')) {
        const user = await sessionUser(env, request);
        if (!user) return json({ error: 'unauthorized' }, 401);

        if (path === '/api/admin/devices' && method === 'GET') return listDevices(env);
        if (path === '/api/admin/requests' && method === 'GET') return listRequests(env);

        let a = path.match(/^\/api\/admin\/device\/([^/]+)$/);
        if (a && method === 'POST') return updateDevice(env, decodeURIComponent(a[1]), request);

        a = path.match(/^\/api\/admin\/request\/(\d+)\/resolve$/);
        if (a && method === 'POST') return resolveRequest(env, a[1]);

        return json({ error: 'not found' }, 404);
      }

      // ---- HTML pages ----------------------------------------------------
      if (path === '/' ) return redirect('/panel');
      if (path === '/login') {
        if ((await managerCount(env)) === 0) return redirect('/setup');
        return html(loginPage());
      }
      if (path === '/setup') {
        if ((await managerCount(env)) > 0) return redirect('/login');
        return html(setupPage());
      }
      if (path === '/panel') {
        const user = await sessionUser(env, request);
        if (!user) return redirect((await managerCount(env)) === 0 ? '/setup' : '/login');
        return html(panelPage());
      }

      return new Response('Not found', { status: 404 });
    } catch (e) {
      return json({ error: 'server_error', detail: String(e) }, 500);
    }
  },
};

// --------------------------------------------------------------------------
// Device handlers
// --------------------------------------------------------------------------

async function devicePolicy(env, id) {
  const now = Date.now();
  // Auto-register unknown devices with filtering ON by default.
  await env.DB.prepare(
    `INSERT INTO devices (id, enabled, unlock_until, policy_version, created_at, last_seen)
     VALUES (?, 1, 0, 1, ?, ?)
     ON CONFLICT(id) DO UPDATE SET last_seen = excluded.last_seen`
  ).bind(id, now, now).run();

  const d = await env.DB.prepare(
    'SELECT enabled, unlock_until, manager_contact, policy_version FROM devices WHERE id = ?'
  ).bind(id).first();

  return json({
    version: d.policy_version,
    enabled: d.enabled === 1,
    unlockUntil: d.unlock_until,
    managerContact: d.manager_contact || '',
  }, 200, { 'Cache-Control': 'no-store' });
}

async function deviceUnlockRequest(env, id, request) {
  let body = {};
  try { body = await request.json(); } catch (_) {}
  const reason = String(body.reason || '').slice(0, 300);
  await env.DB.prepare(
    'INSERT INTO unlock_requests (device_id, reason, ts, status) VALUES (?, ?, ?, \'open\')'
  ).bind(id, reason, Date.now()).run();
  return json({ ok: true });
}

// --------------------------------------------------------------------------
// Admin auth handlers
// --------------------------------------------------------------------------

async function adminSetup(env, request) {
  if ((await managerCount(env)) > 0) return json({ error: 'already_configured' }, 403);
  const { username, password } = await request.json().catch(() => ({}));
  if (!username || !password || password.length < 8) {
    return json({ error: 'invalid' }, 400);
  }
  const { salt, hash } = await hashPassword(password);
  await env.DB.prepare('INSERT INTO managers (username, salt, hash, created_at) VALUES (?, ?, ?, ?)')
    .bind(username, salt, hash, Date.now()).run();
  const s = await createSession(env, username);
  return json({ ok: true }, 200, { 'Set-Cookie': sessionCookie(s.token, s.expires) });
}

async function adminLogin(env, request) {
  const { username, password } = await request.json().catch(() => ({}));
  if (!username || !password) return json({ error: 'invalid' }, 400);
  const row = await env.DB.prepare('SELECT salt, hash FROM managers WHERE username = ?')
    .bind(username).first();
  if (!row) return json({ error: 'bad_credentials' }, 401);
  const ok = await verifyPassword(password, row.salt, row.hash);
  if (!ok) return json({ error: 'bad_credentials' }, 401);
  const s = await createSession(env, username);
  return json({ ok: true }, 200, { 'Set-Cookie': sessionCookie(s.token, s.expires) });
}

async function adminLogout(env, request) {
  await destroySession(env, request);
  return json({ ok: true }, 200, { 'Set-Cookie': clearCookie() });
}

// --------------------------------------------------------------------------
// Admin data handlers
// --------------------------------------------------------------------------

async function listDevices(env) {
  const { results } = await env.DB.prepare(
    `SELECT id, label, enabled, unlock_until, manager_contact, policy_version, last_seen
     FROM devices ORDER BY last_seen DESC`
  ).all();
  return json({ devices: results || [] });
}

async function listRequests(env) {
  const { results } = await env.DB.prepare(
    `SELECT id, device_id, reason, ts, status FROM unlock_requests
     WHERE status = 'open' ORDER BY ts DESC LIMIT 200`
  ).all();
  return json({ requests: results || [] });
}

async function updateDevice(env, id, request) {
  const body = await request.json().catch(() => ({}));
  const sets = [];
  const binds = [];
  let policyChanged = false;

  if (typeof body.enabled !== 'undefined') {
    sets.push('enabled = ?'); binds.push(body.enabled ? 1 : 0); policyChanged = true;
  }
  if (typeof body.unlockUntil !== 'undefined') {
    sets.push('unlock_until = ?'); binds.push(Math.max(0, parseInt(body.unlockUntil, 10) || 0));
    policyChanged = true;
  }
  if (typeof body.managerContact !== 'undefined') {
    sets.push('manager_contact = ?'); binds.push(String(body.managerContact).slice(0, 60));
    policyChanged = true;
  }
  if (typeof body.label !== 'undefined') {
    sets.push('label = ?'); binds.push(String(body.label).slice(0, 80));
  }
  if (!sets.length) return json({ error: 'nothing_to_update' }, 400);

  // Ensure the device row exists.
  await env.DB.prepare(
    `INSERT INTO devices (id, created_at, last_seen) VALUES (?, ?, 0)
     ON CONFLICT(id) DO NOTHING`
  ).bind(id, Date.now()).run();

  if (policyChanged) sets.push('policy_version = policy_version + 1');
  binds.push(id);
  await env.DB.prepare(`UPDATE devices SET ${sets.join(', ')} WHERE id = ?`).bind(...binds).run();
  return json({ ok: true });
}

async function resolveRequest(env, rid) {
  await env.DB.prepare("UPDATE unlock_requests SET status = 'resolved' WHERE id = ?")
    .bind(parseInt(rid, 10)).run();
  return json({ ok: true });
}
