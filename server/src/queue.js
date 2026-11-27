// Asynchronous task queue. Tasks are processed on the server at their time (or ahead of it for things the
// phone has to do itself), stored as "ready", and handed to the phone on its next sync. The phone reports the
// outcome later, even if it was offline for days. Everything is idempotent so lost replies are harmless.
import { processors, TASK_TYPES } from './processors.js';

export const STATUS = {
  queued: 'ממתין לעיבוד',
  processing: 'בעיבוד',
  ready: 'מוכן — ממתין לטלפון',
  delivered: 'נמסר לטלפון',
  done: 'בוצע',
  failed: 'נכשל בטלפון',
  expired: 'פג תוקף',
  error: 'שגיאת עיבוד בשרת',
  cancelled: 'בוטל',
};

/** How long before runAt a task is prepared, so the phone can hold it and run it on time while offline. */
const DEFAULT_LEAD_MS = {
  send_message: 24 * 3600_000,
  prepare_message: 24 * 3600_000,
  reminder: 24 * 3600_000,
  digest: 0,
  fetch_url: 0,
};

const FINAL = new Set(['done', 'failed', 'expired', 'error', 'cancelled']);
const MAX_UPLOADS_PER_DEVICE = 5000;
const MAX_BATCH = 100;

export function backoffMs(attempt, base = 5_000, max = 10 * 60_000) {
  return Math.min(max, base * 2 ** Math.max(0, attempt - 1));
}

export class TaskQueue {
  /**
   * @param {import('./store.js').Store} store
   * @param {{now?: () => number, timeZone?: string, fetch?: typeof fetch, log?: (msg: string) => void}} [opts]
   */
  constructor(store, opts = {}) {
    this.store = store;
    this.now = opts.now ?? Date.now;
    this.timeZone = opts.timeZone ?? 'Asia/Jerusalem';
    this.fetch = opts.fetch;
    this.log = opts.log ?? (() => {});
    this.waiters = new Set();
    this.running = false;
    this.timer = null;
    // Tasks interrupted by a crash while "processing" go back to the queue.
    for (const t of Object.values(this.data.tasks)) {
      if (t.status === 'processing') t.status = 'queued';
    }
  }

  get data() {
    return this.store.data;
  }

  // ---------------- tasks ----------------

  createTask(input, { source = 'api' } = {}) {
    const type = String(input?.type ?? '');
    if (!processors[type]) throw new ValidationError(`סוג משימה לא נתמך: ${type || '(ריק)'}`);
    const now = this.now();
    const runAt = input.runAt === undefined ? now : parseTime(input.runAt);
    if (!Number.isFinite(runAt)) throw new ValidationError('זמן ההרצה (runAt) לא תקין');
    const repeat = input.repeat ? normalizeRepeat(input.repeat) : null;
    const id = input.id && typeof input.id === 'string' ? input.id : this.store.nextId('t');
    if (this.data.tasks[id]) return this.data.tasks[id]; // idempotent create (client retries)
    const task = {
      id,
      type,
      title: String(input.title ?? TASK_TYPES[type]),
      deviceId: input.deviceId ? String(input.deviceId) : null,
      payload: typeof input.payload === 'object' && input.payload ? input.payload : {},
      runAt,
      leadMs: Number.isFinite(input.leadMs) ? Math.max(0, input.leadMs) : DEFAULT_LEAD_MS[type],
      repeat,
      occurrence: input.occurrence ?? 1,
      seriesId: input.seriesId ?? id,
      status: 'queued',
      attempts: 0,
      maxAttempts: Number.isFinite(input.maxAttempts) ? input.maxAttempts : 5,
      nextAttemptAt: 0,
      result: null,
      error: null,
      deviceResult: null,
      history: [{ at: now, status: 'queued', note: `נוצר (${source})` }],
      createdAt: now,
      updatedAt: now,
      source,
    };
    this.data.tasks[id] = task;
    this.store.save();
    this.log(`משימה חדשה ${id} (${TASK_TYPES[type]}) לזמן ${new Date(runAt).toISOString()}`);
    this.kick();
    return task;
  }

  listTasks({ deviceId, status, limit = 200 } = {}) {
    return Object.values(this.data.tasks)
      .filter((t) => (!deviceId || t.deviceId === deviceId) && (!status || t.status === status))
      .sort((a, b) => b.createdAt - a.createdAt)
      .slice(0, limit);
  }

  getTask(id) {
    return this.data.tasks[id] ?? null;
  }

  cancelTask(id) {
    const t = this.data.tasks[id];
    if (!t) return null;
    if (FINAL.has(t.status)) return t;
    const wasDelivered = t.status === 'delivered' || t.status === 'ready';
    this.#setStatus(t, 'cancelled', 'בוטל');
    t.cancelPending = wasDelivered; // tell the phone to drop its local copy
    t.repeat = null;
    this.store.save();
    this.#notify(t.deviceId);
    return t;
  }

  #setStatus(t, status, note) {
    t.status = status;
    t.updatedAt = this.now();
    t.history.push({ at: t.updatedAt, status, note });
    if (t.history.length > 30) t.history.splice(0, t.history.length - 30);
  }

  // ---------------- processing ----------------

  /** Processes every task that is due. Safe to call often; only one pass runs at a time. */
  async tick() {
    if (this.running) return 0;
    this.running = true;
    let processed = 0;
    try {
      const now = this.now();
      const due = Object.values(this.data.tasks).filter(
        (t) => t.status === 'queued' && t.runAt - t.leadMs <= now && t.nextAttemptAt <= now,
      ).sort((a, b) => a.runAt - b.runAt);
      for (const t of due) {
        await this.#process(t);
        processed++;
      }
    } finally {
      this.running = false;
    }
    return processed;
  }

  async #process(t) {
    this.#setStatus(t, 'processing', `ניסיון ${t.attempts + 1}`);
    t.attempts += 1;
    try {
      const result = await processors[t.type](t, {
        timeZone: this.timeZone,
        fetch: this.fetch,
        uploadsFor: (deviceId) => this.data.uploads[deviceId] ?? [],
      });
      if (t.status === 'cancelled') return; // cancelled while running
      t.result = result;
      t.error = null;
      this.#setStatus(t, 'ready', 'העיבוד הושלם, ממתין לסנכרון');
      this.log(`משימה ${t.id} מוכנה`);
      this.#notify(t.deviceId);
      this.#scheduleNextSafely(t);
    } catch (err) {
      t.error = err?.message ?? String(err);
      if (err instanceof ValidationError || err?.permanent || t.attempts >= t.maxAttempts) {
        this.#setStatus(t, 'error', t.error);
        this.log(`משימה ${t.id} נכשלה סופית: ${t.error}`);
        this.#scheduleNextSafely(t);
      } else {
        t.nextAttemptAt = this.now() + backoffMs(t.attempts);
        this.#setStatus(t, 'queued', `שגיאה: ${t.error}; ניסיון חוזר בעוד ${Math.round(backoffMs(t.attempts) / 1000)} שניות`);
      }
    } finally {
      this.store.save();
    }
  }

  /** A broken repeat rule must never change the outcome of the occurrence that just ran. */
  #scheduleNextSafely(t) {
    try {
      this.#scheduleNext(t);
    } catch (e) {
      this.log(`לא ניתן ליצור את המופע הבא של ${t.id}: ${e.message}`);
      t.history.push({ at: this.now(), status: t.status, note: `החזרה נעצרה: ${e.message}` });
    }
  }

  /** Recurring tasks: creates the next occurrence. Missed slots (server was down) are skipped, not bunched. */
  #scheduleNext(t) {
    const r = t.repeat;
    if (!r) return;
    if (r.count && t.occurrence >= r.count) return;
    const step = r.everyMinutes * 60_000;
    let next = t.runAt + step;
    const now = this.now();
    if (next < now - step) next += Math.ceil((now - next) / step) * step;
    this.createTask({
      type: t.type,
      title: t.title,
      deviceId: t.deviceId,
      payload: t.payload,
      runAt: next,
      leadMs: t.leadMs,
      repeat: r,
      occurrence: t.occurrence + 1,
      seriesId: t.seriesId,
      maxAttempts: t.maxAttempts,
    }, { source: 'repeat' });
  }

  start(intervalMs = 1000) {
    if (this.timer) return;
    this.timer = setInterval(() => this.tick().catch((e) => this.log(`שגיאת תור: ${e.message}`)), intervalMs);
    this.timer.unref?.();
  }

  stop() {
    clearInterval(this.timer);
    this.timer = null;
    for (const w of this.waiters) w.resolve(false);
    this.waiters.clear();
  }

  kick() {
    setImmediate(() => this.tick().catch((e) => this.log(`שגיאת תור: ${e.message}`)));
  }

  // ---------------- sync with the phone ----------------

  /**
   * One sync round trip. The request carries everything the phone collected while offline; the reply carries
   * everything the server prepared meanwhile. Each part is acknowledged by id so retries never duplicate work.
   */
  sync(req) {
    const deviceId = String(req?.deviceId ?? '').trim();
    if (!deviceId || deviceId.length > 100) throw new ValidationError('deviceId חסר או לא תקין');
    const now = this.now();

    const device = this.data.devices[deviceId] ?? { id: deviceId, firstSeen: now };
    Object.assign(device, {
      name: String(req.deviceName ?? device.name ?? deviceId).slice(0, 100),
      appVersion: req.appVersion ?? device.appVersion,
      lastSeen: now,
      clockSkewMs: Number.isFinite(req.now) ? req.now - now : 0,
      status: typeof req.status === 'object' && req.status ? req.status : device.status,
    });
    this.data.devices[deviceId] = device;

    // 1. Uploads collected offline (inbox entries, logs, new tasks created on the phone).
    const acceptedUploads = [];
    const seen = (this.data.seenUploads[deviceId] ??= {});
    for (const u of asArray(req.uploads).slice(0, 500)) {
      if (!u?.clientId) continue;
      if (!seen[u.clientId]) {
        seen[u.clientId] = now;
        if (u.kind === 'task') {
          try {
            this.createTask({ ...u.data, id: `d_${u.clientId}`, deviceId }, { source: 'device' });
          } catch (e) {
            this.log(`משימה מהטלפון נדחתה: ${e.message}`);
          }
        } else {
          const list = (this.data.uploads[deviceId] ??= []);
          list.push({ clientId: u.clientId, kind: String(u.kind ?? 'event'), at: Number(u.at) || now, data: u.data ?? null });
          if (list.length > MAX_UPLOADS_PER_DEVICE) list.splice(0, list.length - MAX_UPLOADS_PER_DEVICE);
        }
      }
      acceptedUploads.push(u.clientId);
    }
    pruneSeen(seen, now);

    // 2. The phone confirms it stored these tasks (or cancellations).
    for (const id of asArray(req.received)) {
      const t = this.data.tasks[id];
      if (!t || (t.deviceId && t.deviceId !== deviceId)) continue;
      if (t.status === 'ready') {
        t.deviceId = deviceId;
        this.#setStatus(t, 'delivered', 'הטלפון קיבל את המשימה');
      } else if (t.status === 'cancelled') {
        t.cancelPending = false;
      }
    }

    // 3. Outcomes of tasks the phone executed (possibly long ago while offline).
    const acceptedResults = [];
    for (const r of asArray(req.results)) {
      const t = this.data.tasks[r?.taskId];
      if (!t || (t.deviceId && t.deviceId !== deviceId)) continue;
      acceptedResults.push(r.taskId);
      if (FINAL.has(t.status) && t.deviceResult) continue; // duplicate report
      const status = ['done', 'failed', 'expired'].includes(r.status) ? r.status : 'failed';
      t.deviceResult = { status, note: String(r.note ?? '').slice(0, 1000), at: Number(r.at) || now };
      if (t.status !== 'cancelled') this.#setStatus(t, status, t.deviceResult.note || STATUS[status]);
    }

    // 4. What the server prepared for this phone.
    const deliverable = Object.values(this.data.tasks)
      .filter((t) => t.status === 'ready' && (t.deviceId === deviceId || t.deviceId === null))
      .sort((a, b) => a.runAt - b.runAt);
    const tasks = deliverable.slice(0, MAX_BATCH).map((t) => ({
      id: t.id, type: t.type, title: t.title, runAt: t.runAt, result: t.result, occurrence: t.occurrence, createdAt: t.createdAt,
    }));
    const cancelled = Object.values(this.data.tasks)
      .filter((t) => t.status === 'cancelled' && t.cancelPending && t.deviceId === deviceId)
      .map((t) => t.id);

    this.store.save();
    return {
      serverTime: now,
      tasks,
      cancelled,
      acceptedUploads,
      acceptedResults,
      hasMore: deliverable.length > MAX_BATCH,
      nextPollMs: 25_000,
    };
  }

  /** Long poll: resolves true as soon as something is ready for [deviceId], or false after [timeoutMs]. */
  waitForChanges(deviceId, timeoutMs) {
    const hasWork = () => Object.values(this.data.tasks).some(
      (t) => (t.status === 'ready' && (t.deviceId === deviceId || t.deviceId === null)) ||
        (t.status === 'cancelled' && t.cancelPending && t.deviceId === deviceId),
    );
    if (hasWork()) return Promise.resolve(true);
    return new Promise((resolve) => {
      const waiter = {
        deviceId,
        resolve: (v) => {
          clearTimeout(timer);
          this.waiters.delete(waiter);
          resolve(v);
        },
      };
      const timer = setTimeout(() => waiter.resolve(false), timeoutMs);
      this.waiters.add(waiter);
    });
  }

  #notify(deviceId) {
    for (const w of [...this.waiters]) {
      if (!deviceId || w.deviceId === deviceId) w.resolve(true);
    }
  }

  stats() {
    const counts = {};
    for (const t of Object.values(this.data.tasks)) counts[t.status] = (counts[t.status] ?? 0) + 1;
    return { counts, devices: Object.values(this.data.devices), waiting: this.waiters.size };
  }
}

export class ValidationError extends Error {}

function asArray(v) {
  return Array.isArray(v) ? v : [];
}

function parseTime(v) {
  if (typeof v === 'number') return v;
  const ms = Date.parse(String(v));
  return Number.isNaN(ms) ? NaN : ms;
}

function normalizeRepeat(r) {
  const everyMinutes = Number(r.everyMinutes);
  if (!Number.isFinite(everyMinutes) || everyMinutes < 1) throw new ValidationError('repeat.everyMinutes חייב להיות 1 או יותר');
  const count = r.count === undefined || r.count === null ? null : Number(r.count);
  if (count !== null && (!Number.isInteger(count) || count < 1)) throw new ValidationError('repeat.count חייב להיות מספר שלם חיובי');
  return { everyMinutes, count };
}

function pruneSeen(seen, now) {
  const keys = Object.keys(seen);
  if (keys.length < 20_000) return;
  for (const k of keys) if (now - seen[k] > 30 * 86_400_000) delete seen[k];
}
