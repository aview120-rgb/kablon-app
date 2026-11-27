// Server-side processors. They run while the phone is offline or switched off and turn a queued task
// into a "ready" result that the phone picks up on its next sync.
// Every processor is (task, ctx) => Promise<result>; throwing marks the attempt as failed (retried with backoff).

const HE_DAYS = ['ראשון', 'שני', 'שלישי', 'רביעי', 'חמישי', 'שישי', 'שבת'];
const HE_MONTHS = ['ינואר', 'פברואר', 'מרץ', 'אפריל', 'מאי', 'יוני', 'יולי', 'אוגוסט', 'ספטמבר', 'אוקטובר', 'נובמבר', 'דצמבר'];

export const TASK_TYPES = {
  send_message: 'שליחת הודעה',
  prepare_message: 'הכנת הודעה (ללא שליחה)',
  reminder: 'תזכורת',
  digest: 'סיכום הודעות',
  fetch_url: 'בדיקת כתובת אינטרנט',
};

/** Errors that retrying cannot fix (bad input). The task fails at once instead of being retried. */
export class InvalidTaskError extends Error {
  permanent = true;
}

const pad = (n) => String(n).padStart(2, '0');

/** Parts of a date in a time zone (default Asia/Jerusalem), without external libraries. */
export function zonedParts(ms, timeZone = 'Asia/Jerusalem') {
  const fmt = new Intl.DateTimeFormat('en-US', {
    timeZone, year: 'numeric', month: 'numeric', day: 'numeric',
    hour: 'numeric', minute: 'numeric', weekday: 'short', hourCycle: 'h23',
  });
  const p = Object.fromEntries(fmt.formatToParts(new Date(ms)).map((x) => [x.type, x.value]));
  const weekday = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'].indexOf(p.weekday);
  return { year: +p.year, month: +p.month, day: +p.day, hour: +p.hour % 24, minute: +p.minute, weekday };
}

/**
 * Replaces {{date}}, {{time}}, {{weekday}}, {{hebrew_date}}, {{occurrence}} and {{vars.x}} in a template.
 * Unknown placeholders are left as they are so nothing is silently lost.
 */
export function renderTemplate(text, { at, timeZone, occurrence = 1, vars = {} } = {}) {
  const z = zonedParts(at ?? Date.now(), timeZone);
  const values = {
    date: `${pad(z.day)}/${pad(z.month)}/${z.year}`,
    time: `${pad(z.hour)}:${pad(z.minute)}`,
    weekday: `יום ${HE_DAYS[z.weekday]}`,
    hebrew_date: `${z.day} ב${HE_MONTHS[z.month - 1]} ${z.year}`,
    occurrence: String(occurrence),
  };
  return String(text ?? '').replace(/\{\{\s*([\w.]+)\s*\}\}/g, (m, key) => {
    if (key.startsWith('vars.')) return vars[key.slice(5)] !== undefined ? String(vars[key.slice(5)]) : m;
    return values[key] ?? m;
  });
}

function requireText(task, field = 'text', max = 1000) {
  const v = task.payload?.[field];
  if (typeof v !== 'string' || !v.trim()) throw new InvalidTaskError(`שדה חובה חסר: ${field}`);
  if (v.length > max) throw new InvalidTaskError(`השדה ${field} ארוך מ-${max} תווים`);
  return v;
}

async function messageProcessor(task, ctx) {
  const p = task.payload ?? {};
  const text = renderTemplate(requireText(task), { at: task.runAt, timeZone: ctx.timeZone, occurrence: task.occurrence, vars: p.vars });
  if (!p.recipient || /[,;]/.test(p.recipient)) throw new InvalidTaskError('נדרש נמען אחד בדיוק');
  if (text.length > 1000) throw new InvalidTaskError('ההודעה ארוכה מ-1000 תווים');
  return {
    channel: p.channel ?? 'WHATSAPP',
    packageName: p.packageName ?? (p.channel === 'SMS' ? '' : 'com.whatsapp'),
    appLabel: p.appLabel ?? (p.channel === 'SMS' ? 'SMS' : 'WhatsApp'),
    recipient: String(p.recipient),
    recipientLabel: String(p.recipientLabel ?? p.recipient),
    text,
    autoSend: task.type === 'send_message',
    executeAt: task.runAt,
    maxLateMinutes: Number.isFinite(p.maxLateMinutes) ? p.maxLateMinutes : 15,
  };
}

async function reminderProcessor(task, ctx) {
  const text = renderTemplate(requireText(task), { at: task.runAt, timeZone: ctx.timeZone, occurrence: task.occurrence, vars: task.payload?.vars });
  return { title: task.payload?.title ?? 'תזכורת', text, executeAt: task.runAt, maxLateMinutes: task.payload?.maxLateMinutes ?? 24 * 60 };
}

const MONEY = /(₪|\$|€|£)\s?\d[\d,.]*|\d[\d,.]*\s?(₪|ש"ח|שח|ils|nis|usd)/gi;

/** Summarizes inbox entries the phone uploaded earlier (opt-in on the phone). Runs fully on the server. */
async function digestProcessor(task, ctx) {
  const hours = Number(task.payload?.hours ?? 24);
  const since = task.runAt - hours * 3600_000;
  const entries = (ctx.uploadsFor(task.deviceId) ?? [])
    .filter((u) => u.kind === 'inbox' && (u.at ?? 0) >= since && (u.at ?? 0) <= task.runAt);
  const bySender = new Map();
  for (const e of entries) {
    const key = `${e.data?.app ?? ''}|${e.data?.sender ?? 'לא ידוע'}`;
    if (!bySender.has(key)) bySender.set(key, []);
    bySender.get(key).push(e);
  }
  const lines = [];
  for (const [key, list] of [...bySender.entries()].sort((a, b) => b[1].length - a[1].length)) {
    const [app, sender] = key.split('|');
    const texts = list.map((x) => String(x.data?.text ?? ''));
    const questions = texts.filter((t) => t.includes('?')).length;
    const money = texts.flatMap((t) => t.match(MONEY) ?? []);
    let line = `• ${sender}${app ? ` (${app})` : ''}: ${list.length} הודעות`;
    if (questions) line += `, ${questions} שאלות`;
    if (money.length) line += `, סכומים: ${[...new Set(money)].slice(0, 3).join(' ')}`;
    line += ` — אחרונה: "${texts.at(-1).slice(0, 60)}"`;
    lines.push(line);
  }
  const text = entries.length
    ? `סיכום ${hours} השעות האחרונות: ${entries.length} הודעות מ-${bySender.size} שולחים\n${lines.join('\n')}`
    : `אין הודעות חדשות ב-${hours} השעות האחרונות.`;
  return { title: 'סיכום הודעות', text, count: entries.length, executeAt: task.runAt, maxLateMinutes: 24 * 60 };
}

/** Checks a URL from the server (works while the phone is off) and reports status + page title. */
async function fetchUrlProcessor(task, ctx) {
  const url = requireText(task, 'url', 2000);
  if (!/^https?:\/\//i.test(url)) throw new InvalidTaskError('הכתובת חייבת להתחיל ב-http:// או https://');
  const started = Date.now();
  const res = await (ctx.fetch ?? fetch)(url, { redirect: 'follow', signal: AbortSignal.timeout(ctx.fetchTimeoutMs ?? 15_000) });
  const body = (await res.text()).slice(0, 200_000);
  const title = body.match(/<title[^>]*>([^<]{0,200})<\/title>/i)?.[1]?.trim() ?? '';
  const ok = res.status < 400;
  const contains = task.payload?.contains;
  const found = contains ? body.includes(contains) : undefined;
  let text = `${ok ? 'האתר זמין' : 'האתר החזיר שגיאה'} (${res.status}) תוך ${Date.now() - started} מ"ש`;
  if (title) text += `\nכותרת: ${title}`;
  if (contains) text += `\n${found ? 'נמצא' : 'לא נמצא'} הטקסט "${contains}"`;
  return { title: 'בדיקת אתר', text, httpStatus: res.status, found, executeAt: task.runAt, maxLateMinutes: 24 * 60 };
}

export const processors = {
  send_message: messageProcessor,
  prepare_message: messageProcessor,
  reminder: reminderProcessor,
  digest: digestProcessor,
  fetch_url: fetchUrlProcessor,
};
