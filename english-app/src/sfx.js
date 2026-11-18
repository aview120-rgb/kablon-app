// Short built-in sound effects (original tones made for this app, bundled – no internet needed).
const cache = {};
export function sfx(name) {
  try {
    const a = cache[name] || (cache[name] = new Audio(`sfx/${name}.mp3`));
    a.currentTime = 0;
    a.volume = 0.7;
    a.play().catch(() => {});
  } catch { /* audio not available */ }
}
