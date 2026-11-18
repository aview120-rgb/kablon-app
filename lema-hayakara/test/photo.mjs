import fs from 'fs';
import { makeImage } from '../src/engine/raster.js';
import { photoLines } from '../src/engine/photo.js';
import { digitizeImage, compileDesign, toPattern, designStats, centerObjects } from '../src/engine/design.js';
import { writeDST } from '../src/engine/formats.js';
const W = 300, H = 300, img = makeImage(W, H);
for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) {
  const r = Math.hypot(x - 150, y - 150);
  const v = r > 140 ? 255 : Math.round(255 * (0.5 + 0.5 * Math.sin(x / 25) * Math.cos(y / 30)));
  img.data.set([v, v * 0.8, 255 - v, 255], (y * W + x) * 4);
}
let t = Date.now();
const p = photoLines(img, { widthMm: 90 });
const c = compileDesign(centerObjects(p.objects));
console.log('photo lines', Date.now() - t, designStats(c));
fs.writeFileSync('/tmp/claude-0/out/photo.dst', writeDST(toPattern(c)));
t = Date.now();
const d = digitizeImage(img, { widthMm: 90, colors: 8, mode: 'photo', removeBackground: true });
const c2 = compileDesign(centerObjects(d.objects));
console.log('photo color', Date.now() - t, designStats(c2));
fs.writeFileSync('/tmp/claude-0/out/photoc.dst', writeDST(toPattern(c2)));
