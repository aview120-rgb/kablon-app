// Password hashing (PBKDF2 via WebCrypto) and session helpers.

const PBKDF2_ITERS = 100_000;
const SESSION_TTL_MS = 1000 * 60 * 60 * 12; // 12 hours

function bytesToHex(bytes) {
  return [...bytes].map((b) => b.toString(16).padStart(2, '0')).join('');
}
function hexToBytes(hex) {
  const out = new Uint8Array(hex.length / 2);
  for (let i = 0; i < out.length; i++) out[i] = parseInt(hex.substr(i * 2, 2), 16);
  return out;
}

export async function hashPassword(password, saltHex) {
  const enc = new TextEncoder();
  const salt = saltHex ? hexToBytes(saltHex) : crypto.getRandomValues(new Uint8Array(16));
  const keyMaterial = await crypto.subtle.importKey(
    'raw', enc.encode(password), 'PBKDF2', false, ['deriveBits']
  );
  const bits = await crypto.subtle.deriveBits(
    { name: 'PBKDF2', salt, iterations: PBKDF2_ITERS, hash: 'SHA-256' },
    keyMaterial, 256
  );
  return { salt: bytesToHex(salt), hash: bytesToHex(new Uint8Array(bits)) };
}

// Constant-time string comparison.
function timingSafeEqual(a, b) {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}

export async function verifyPassword(password, saltHex, expectedHashHex) {
  const { hash } = await hashPassword(password, saltHex);
  return timingSafeEqual(hash, expectedHashHex);
}

export function newToken() {
  return bytesToHex(crypto.getRandomValues(new Uint8Array(32)));
}

export async function createSession(env, username) {
  const token = newToken();
  const expires = Date.now() + SESSION_TTL_MS;
  await env.DB.prepare('INSERT INTO sessions (token, username, expires_at) VALUES (?, ?, ?)')
    .bind(token, username, expires).run();
  return { token, expires };
}

export async function sessionUser(env, request) {
  const token = readCookie(request, 'sid');
  if (!token) return null;
  const row = await env.DB.prepare('SELECT username, expires_at FROM sessions WHERE token = ?')
    .bind(token).first();
  if (!row) return null;
  if (row.expires_at < Date.now()) {
    await env.DB.prepare('DELETE FROM sessions WHERE token = ?').bind(token).run();
    return null;
  }
  return row.username;
}

export async function destroySession(env, request) {
  const token = readCookie(request, 'sid');
  if (token) await env.DB.prepare('DELETE FROM sessions WHERE token = ?').bind(token).run();
}

export function readCookie(request, name) {
  const header = request.headers.get('Cookie') || '';
  for (const part of header.split(';')) {
    const [k, v] = part.trim().split('=');
    if (k === name) return decodeURIComponent(v || '');
  }
  return null;
}

export function sessionCookie(token, expiresMs) {
  const maxAge = Math.max(0, Math.floor((expiresMs - Date.now()) / 1000));
  return `sid=${token}; HttpOnly; Secure; SameSite=Strict; Path=/; Max-Age=${maxAge}`;
}

export function clearCookie() {
  return 'sid=; HttpOnly; Secure; SameSite=Strict; Path=/; Max-Age=0';
}
