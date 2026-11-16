// Text-to-speech: device voices only, no audio files, nothing sent to a server by us.
// Returns a status so the UI can explain *why* there is no sound instead of staying silent.
import { Capacitor } from '@capacitor/core';

let native = null;
let nativeLang = null; // the English variant this phone's engine actually supports
const EN_VARIANTS = ['en-US', 'en-GB', 'en-AU', 'en-IN', 'en'];

async function nativeTts() {
  if (native !== null) return native;
  if (!Capacitor.isNativePlatform()) return (native = false);
  try { native = (await import('@capacitor-community/text-to-speech')).TextToSpeech; } catch { native = false; }
  return native;
}

// Android's engine starts asynchronously; wait until it answers (max ~3s).
async function ensureNativeLang(n) {
  if (nativeLang) return nativeLang;
  for (let attempt = 0; attempt < 6; attempt++) {
    try {
      for (const lang of EN_VARIANTS) {
        const { supported } = await n.isLanguageSupported({ lang });
        if (supported) return (nativeLang = lang);
      }
      try {
        const { languages } = await n.getSupportedLanguages();
        const any = (languages || []).find((l) => /^en/i.test(l));
        if (any) return (nativeLang = any);
      } catch {}
      return null; // engine ready, but no English voice installed
    } catch {
      await new Promise((r) => setTimeout(r, 500)); // engine not initialised yet
    }
  }
  return undefined; // no engine answered at all
}

export function ttsAvailable() {
  return Capacitor.isNativePlatform() || (typeof window !== 'undefined' && 'speechSynthesis' in window);
}

// Call once at startup so the first word is not lost while the engine boots.
export async function warmUp() {
  const n = await nativeTts();
  if (n) ensureNativeLang(n);
}

/** @returns {Promise<'ok'|'no-english'|'no-engine'|'error'>} */
export async function speak(text, rate = 0.85) {
  const n = await nativeTts();
  if (n) {
    const lang = await ensureNativeLang(n);
    if (lang === undefined) return webSpeak(text, rate) ? 'ok' : 'no-engine';
    if (lang === null) return webSpeak(text, rate) ? 'ok' : 'no-english';
    try {
      // not awaited: the native promise resolves only when speaking ends
      n.speak({ text, lang, rate, pitch: 1, volume: 1, queueStrategy: 0 }).catch(() => {});
      return 'ok';
    } catch { return 'error'; }
  }
  return webSpeak(text, rate) ? 'ok' : 'no-engine';
}

function webSpeak(text, rate) {
  try {
    if (typeof window === 'undefined' || !('speechSynthesis' in window)) return false;
    const voices = speechSynthesis.getVoices();
    const v = voices.find((x) => x.lang?.toLowerCase().startsWith('en'));
    if (voices.length && !v) return false;
    speechSynthesis.cancel();
    const u = new SpeechSynthesisUtterance(text); u.lang = 'en-US'; u.rate = rate;
    if (v) u.voice = v;
    speechSynthesis.speak(u);
    return true;
  } catch { return false; }
}

// Opens Android's "install voice data" screen.
export async function openVoiceInstall() {
  const n = await nativeTts();
  if (!n) return false;
  try { await n.openInstall(); return true; } catch { return false; }
}
