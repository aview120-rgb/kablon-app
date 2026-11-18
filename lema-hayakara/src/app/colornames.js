// Hebrew colour names for the needle sheet (nearest named colour).
const NAMES = [
  ['#000000', 'שחור'], ['#ffffff', 'לבן'], ['#f5f0e1', 'שמנת'], ['#808080', 'אפור'], ['#c0c0c0', 'כסוף / אפור בהיר'],
  ['#404040', 'אפור כהה'], ['#ff0000', 'אדום'], ['#b00020', 'אדום כהה / בורדו'], ['#7b1e3a', 'יין'], ['#ff6f91', 'ורוד'],
  ['#ffc0cb', 'ורוד בהיר'], ['#e91e63', 'פוקסיה'], ['#ff7f00', 'כתום'], ['#ffb347', 'כתום בהיר'], ['#ffd700', 'זהב / צהוב חם'],
  ['#ffff00', 'צהוב'], ['#fff3a0', 'צהוב בהיר'], ['#c8a165', 'בז׳'], ['#8b5a2b', 'חום'], ['#5c3a1e', 'חום כהה'],
  ['#d2b48c', 'חום בהיר'], ['#00a000', 'ירוק'], ['#006400', 'ירוק כהה'], ['#90ee90', 'ירוק בהיר'], ['#6b8e23', 'ירוק זית'],
  ['#00c9a7', 'טורקיז'], ['#40e0d0', 'תכלת-טורקיז'], ['#87ceeb', 'תכלת'], ['#1e90ff', 'כחול'], ['#0033a0', 'כחול רויאל'],
  ['#001f5b', 'כחול נייבי'], ['#4b0082', 'אינדיגו'], ['#8a2be2', 'סגול'], ['#c8a2c8', 'לילך'], ['#e6e6fa', 'לבנדר'],
  ['#b87333', 'נחושת'], ['#d4af37', 'זהב מתכתי'],
];
function rgb(h) { h = h.replace('#', ''); return [parseInt(h.slice(0, 2), 16), parseInt(h.slice(2, 4), 16), parseInt(h.slice(4, 6), 16)]; }
export function colorName(hex) {
  const [r, g, b] = rgb(hex);
  let best = NAMES[0][1], bd = Infinity;
  for (const [h, n] of NAMES) {
    const [R, G, B] = rgb(h);
    const rm = (r + R) / 2;
    const d = (2 + rm / 256) * (r - R) ** 2 + 4 * (g - G) ** 2 + (2 + (255 - rm) / 256) * (b - B) ** 2;
    if (d < bd) { bd = d; best = n; }
  }
  return best;
}
export function textColorOn(hex) {
  const [r, g, b] = rgb(hex);
  return 0.299 * r + 0.587 * g + 0.114 * b > 150 ? '#111' : '#fff';
}
