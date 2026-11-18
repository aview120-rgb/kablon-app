// Text-to-speech in English AND Hebrew, using the phone's own voices (nothing sent to a server by us).
// Returns a status so the UI can explain *why* there is no sound instead of staying silent.
import { Capacitor } from '@capacitor/core';

// IMPORTANT: a Capacitor plugin object is a Proxy that answers *every* property, including `then`.
// Returning it from an async function (or awaiting it) makes JS treat it as a promise and wait forever.
// So we keep it inside a plain wrapper object { tts } and never return/await the plugin itself.
let holder = null; // { tts: plugin | null }
const VARIANTS = { en: ['en-US', 'en-GB', 'en-AU', 'en-IN', 'en'], he: ['he-IL', 'iw-IL', 'he', 'iw'] };
const found = { en: undefined, he: undefined }; // undefined = not checked yet, null = not on this phone
let seq = 0; // increases on every new speech request, so old sequences stop

async function nativeTts() {
  if (holder) return holder;
  if (!Capacitor.isNativePlatform()) return (holder = { tts: null });
  try {
    const mod = await import('@capacitor-community/text-to-speech');
    holder = { tts: mod.TextToSpeech };
  } catch { holder = { tts: null }; }
  return holder;
}

// Android's engine starts asynchronously; wait until it answers (max ~3s).
// Resolves to the supported tag, null (engine ready, language missing) or undefined (no engine).
async function nativeLang(n, lang) {
  if (found[lang]) return found[lang];
  if (found[lang] === null) return null;
  for (let attempt = 0; attempt < 6; attempt++) {
    try {
      for (const tag of VARIANTS[lang]) {
        const { supported } = await n.isLanguageSupported({ lang: tag });
        if (supported) return (found[lang] = tag);
      }
      try {
        const { languages } = await n.getSupportedLanguages();
        const re = lang === 'he' ? /^(he|iw)/i : /^en/i;
        const any = (languages || []).find((l) => re.test(l));
        if (any) return (found[lang] = any);
      } catch {}
      return (found[lang] = null);
    } catch {
      await new Promise((r) => setTimeout(r, 500)); // engine not initialised yet
    }
  }
  return undefined;
}

// Makes Hebrew study text readable aloud: "אתה / את" -> "אתה, את", drops "(…)" notes.
export function speechText(text) {
  return String(text).replace(/\s*\([^)]*\)/g, '').replace(/\s*\/\s*/g, ', ').replace(/[״"]/g, '').trim();
}
export function langOf(text) {
  return /[֐-׿]/.test(text) ? 'he' : 'en';
}

export function ttsAvailable() {
  return Capacitor.isNativePlatform() || (typeof window !== 'undefined' && 'speechSynthesis' in window);
}

// Call once at startup so the first word is not lost while the engine boots.
export async function warmUp() {
  const { tts } = await nativeTts();
  if (tts) { await nativeLang(tts, 'en'); nativeLang(tts, 'he'); }
}

export function stopSpeaking() {
  seq += 1;
  nativeTts().then(({ tts }) => tts && tts.stop().catch(() => {}));
  try { if (typeof window !== 'undefined' && window.speechSynthesis) speechSynthesis.cancel(); } catch {}
}

/**
 * Speaks one or more parts in order, each in its own language.
 * parts: [{ text, lang?: 'en'|'he', rate? }]
 * @returns {Promise<'ok'|'no-english'|'no-engine'|'error'>} status of the first English part (Hebrew is best-effort)
 */
export async function speakParts(parts, rate = 0.85) {
  const my = ++seq;
  const list = parts.filter((p) => p && p.text).map((p) => ({ lang: p.lang || langOf(p.text), text: p.lang === 'he' || langOf(p.text) === 'he' ? speechText(p.text) : p.text, rate: p.rate || rate }));
  if (!list.length) return 'ok';
  const { tts: n } = await nativeTts();
  let status = 'ok';
  if (n) {
    try { await n.stop(); } catch {}
    for (const p of list) {
      if (my !== seq) return status;
      const tag = await nativeLang(n, p.lang);
      if (!tag) {
        if (p.lang === 'en') status = tag === null ? 'no-english' : 'no-engine';
        continue; // Hebrew voice missing: skip quietly, the text is on screen anyway
      }
      try {
        // Hebrew sounds best a little faster than the slowed-down English
        await n.speak({ text: p.text, lang: tag, rate: p.lang === 'he' ? Math.max(p.rate, 1) : p.rate, pitch: 1, volume: 1, queueStrategy: 0 });
      } catch { if (p.lang === 'en') status = 'error'; }
    }
    return status;
  }
  return webSpeak(list) ? 'ok' : 'no-engine';
}

export function speak(text, rate = 0.85) {
  return speakParts([{ text }], rate);
}

function webSpeak(list) {
  try {
    if (typeof window === 'undefined' || !window.speechSynthesis) return false;
    const voices = speechSynthesis.getVoices();
    speechSynthesis.cancel();
    let any = false;
    for (const p of list) {
      const re = p.lang === 'he' ? /^(he|iw)/i : /^en/i;
      const v = voices.find((x) => re.test(x.lang || ''));
      if (voices.length && !v) continue;
      const u = new SpeechSynthesisUtterance(p.text);
      u.lang = p.lang === 'he' ? 'he-IL' : 'en-US'; u.rate = p.lang === 'he' ? 1 : p.rate;
      if (v) u.voice = v;
      speechSynthesis.speak(u);
      if (p.lang === 'en') any = true;
    }
    return any || list.every((p) => p.lang === 'he');
  } catch { return false; }
}

// Opens Android's "install voice data" screen.
export async function openVoiceInstall() {
  const { tts: n } = await nativeTts();
  if (!n) return false;
  try { await n.openInstall(); return true; } catch { return false; }
}
