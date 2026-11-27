// HTTP API (no external dependencies). All JSON, Bearer-token auth, Hebrew error messages.
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { ValidationError, STATUS } from './queue.js';
import { TASK_TYPES } from './processors.js';

const PUBLIC_DIR = path.join(path.dirname(fileURLToPath(import.meta.url)), '..', 'public');
const MAX_BODY = 1024 * 1024;

class HttpError extends Error {
  constructor(status, message) {
    super(message);
    this.status = status;
  }
}

function safeEqual(a, b) {
  const x = Buffer.from(String(a));
  const y = Buffer.from(String(b));
  return x.length === y.length && crypto.timingSafeEqual(x, y);
}

function readJson(req) {
  return new Promise((resolve, reject) => {
    let size = 0;
    const chunks = [];
    req.on('data', (c) => {
      size += c.length;
      if (size > MAX_BODY) {
        reject(new HttpError(413, 'הבקשה גדולה מדי'));
        req.destroy();
        return;
      }
      chunks.push(c);
    });
    req.on('end', () => {
      if (!chunks.length) return resolve({});
      try {
        resolve(JSON.parse(Buffer.concat(chunks).toString('utf8')));
      } catch {
        reject(new HttpError(400, 'JSON לא תקין'));
      }
    });
    req.on('error', reject);
  });
}

function send(res, status, body) {
  const json = JSON.stringify(body);
  res.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': Buffer.byteLength(json),
    'Cache-Control': 'no-store',
    'Access-Control-Allow-Origin': '*',
    'Access-Control-Allow-Headers': 'Authorization, Content-Type',
    'Access-Control-Allow-Methods': 'GET, POST, DELETE, OPTIONS',
  });
  res.end(json);
}

/**
 * @param {import('./queue.js').TaskQueue} queue
 * @param {{token: string, log?: (m: string) => void}} opts
 */
export function createServer(queue, { token, log = () => {} }) {
  if (!token) throw new Error('חובה להגדיר token');

  const routes = [
    ['GET', /^\/health$/, false, () => ({ ok: true, time: Date.now(), tasks: queue.stats().counts })],
    ['GET', /^\/api\/v1\/meta$/, true, () => ({ types: TASK_TYPES, statuses: STATUS, serverTime: Date.now() })],
    ['GET', /^\/api\/v1\/tasks$/, true, (req, url) => ({
      tasks: queue.listTasks({
        deviceId: url.searchParams.get('deviceId') || undefined,
        status: url.searchParams.get('status') || undefined,
        limit: Math.min(1000, Number(url.searchParams.get('limit')) || 200),
      }),
    })],
    ['POST', /^\/api\/v1\/tasks$/, true, async (req) => {
      const body = await readJson(req);
      const list = Array.isArray(body) ? body : [body];
      if (list.length > 100) throw new HttpError(400, 'אפשר ליצור עד 100 משימות בבקשה אחת');
      const created = list.map((t) => queue.createTask(t));
      return { status: 201, body: Array.isArray(body) ? { tasks: created } : { task: created[0] } };
    }],
    ['GET', /^\/api\/v1\/tasks\/([\w-]+)$/, true, (req, url, m) => {
      const t = queue.getTask(m[1]);
      if (!t) throw new HttpError(404, 'משימה לא נמצאה');
      return { task: t };
    }],
    ['DELETE', /^\/api\/v1\/tasks\/([\w-]+)$/, true, (req, url, m) => {
      const t = queue.cancelTask(m[1]);
      if (!t) throw new HttpError(404, 'משימה לא נמצאה');
      return { task: t };
    }],
    ['GET', /^\/api\/v1\/devices$/, true, () => ({ devices: queue.stats().devices })],
    ['POST', /^\/api\/v1\/sync$/, true, async (req) => queue.sync(await readJson(req))],
    ['GET', /^\/api\/v1\/wait$/, true, async (req, url) => {
      const deviceId = url.searchParams.get('deviceId');
      if (!deviceId) throw new HttpError(400, 'deviceId חסר');
      const timeout = Math.max(1, Math.min(55, Number(url.searchParams.get('timeout')) || 25)) * 1000;
      const changed = await queue.waitForChanges(deviceId, timeout);
      return { changed, serverTime: Date.now() };
    }],
  ];

  const server = http.createServer(async (req, res) => {
    const url = new URL(req.url, 'http://localhost');
    const started = Date.now();
    try {
      if (req.method === 'OPTIONS') return send(res, 204, {});
      if (req.method === 'GET' && (url.pathname === '/' || url.pathname === '/index.html')) {
        const html = fs.readFileSync(path.join(PUBLIC_DIR, 'index.html'));
        res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-store' });
        return res.end(html);
      }
      const route = routes.find(([method, re]) => method === req.method && re.test(url.pathname));
      if (!route) {
        const known = routes.some(([, re]) => re.test(url.pathname));
        throw new HttpError(known ? 405 : 404, known ? 'שיטה לא נתמכת' : 'נתיב לא נמצא');
      }
      const [, re, auth, handler] = route;
      if (auth) {
        const header = req.headers.authorization ?? '';
        const given = header.startsWith('Bearer ') ? header.slice(7) : '';
        if (!given || !safeEqual(given, token)) throw new HttpError(401, 'אסימון גישה שגוי או חסר');
      }
      const out = await handler(req, url, url.pathname.match(re));
      if (out && typeof out.status === 'number' && 'body' in out) send(res, out.status, out.body);
      else send(res, 200, out);
    } catch (err) {
      const status = err instanceof HttpError ? err.status : err instanceof ValidationError ? 400 : 500;
      if (status === 500) log(`שגיאה פנימית: ${err.stack ?? err}`);
      if (!res.headersSent) send(res, status, { error: err.message ?? 'שגיאה' });
    } finally {
      if (url.pathname !== '/api/v1/wait') log(`${req.method} ${url.pathname} ${res.statusCode} ${Date.now() - started}ms`);
    }
  });
  // Long polls stay open up to 55 s.
  server.requestTimeout = 70_000;
  server.headersTimeout = 20_000;
  server.keepAliveTimeout = 65_000;
  return server;
}
