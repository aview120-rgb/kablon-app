// Text-to-speech: device voices only, no audio files, nothing sent to a server by us.
import { Capacitor } from '@capacitor/core';
let native = null;
async function nativeTts() {
  if (native !== null) return native;
  if (!Capacitor.isNativePlatform()) return (native = false);
  try { native = (await import('@capacitor-community/text-to-speech')).TextToSpeech; } catch { native = false; }
  return native;
}
export function ttsAvailable() {
  return Capacitor.isNativePlatform() || (typeof window !== 'undefined' && 'speechSynthesis' in window);
}
export async function speak(text, rate = 0.85) {
  const n = await nativeTts();
  try {
    if (n) { await n.stop().catch(() => {}); await n.speak({ text, lang: 'en-US', rate, pitch: 1, volume: 1, category: 'ambient' }); return true; }
    if ('speechSynthesis' in window) {
      speechSynthesis.cancel();
      const u = new SpeechSynthesisUtterance(text); u.lang = 'en-US'; u.rate = rate;
      const v = speechSynthesis.getVoices().find((x) => x.lang?.startsWith('en'));
      if (v) u.voice = v;
      speechSynthesis.speak(u); return true;
    }
  } catch { /* no voice installed */ }
  return false;
}
