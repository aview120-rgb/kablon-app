import { makeImage } from '../src/engine/raster.js';
import { digitizeImage, compileDesign, toPattern, designStats, centerObjects } from '../src/engine/design.js';
import { writeDST, writePES, writeJEF, writeEXP, readDST } from '../src/engine/formats.js';
import fs from 'fs';
const W = 400, H = 300;
const img = makeImage(W, H);
for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) {
  const i = (y * W + x) * 4;
  let c = [255, 255, 255];
  const dx = x - 140, dy = y - 150, r = Math.hypot(dx, dy);
  if (r < 110) c = [220, 30, 40];
  if (r < 60) c = [255, 255, 255];
  if (r < 40) c = [20, 60, 200];
  if (x > 270 && x < 380 && y > 60 && y < 75) c = [20, 20, 20]; // thin bar
  if (x > 270 && x < 285 && y > 60 && y < 240) c = [20, 20, 20];
  if (Math.abs(x - 330) + Math.abs(y - 180) < 40) c = [30, 160, 60];
  img.data.set([...c, 255], i);
}
let t = Date.now();
const d = digitizeImage(img, { widthMm: 100, colors: 5 });
console.log('digitize ms', Date.now() - t, 'objects', d.objects.length, d.objects.map(o => o.color + ':' + o.shapes.length + ':' + o.shapes.map(s => s.width?.toFixed(1)).join('/')));
t = Date.now();
const objs = centerObjects(d.objects);
const comp = compileDesign(objs, {});
console.log('compile ms', Date.now() - t, designStats(comp));
const pat = toPattern(comp, { name: 'test' });
fs.mkdirSync('/tmp/claude-0/out', { recursive: true });
fs.writeFileSync('/tmp/claude-0/out/t.dst', writeDST(pat));
fs.writeFileSync('/tmp/claude-0/out/t.pes', writePES(pat));
fs.writeFileSync('/tmp/claude-0/out/t.jef', writeJEF(pat));
fs.writeFileSync('/tmp/claude-0/out/t.exp', writeEXP(pat));
const back = readDST(fs.readFileSync('/tmp/claude-0/out/t.dst'));
console.log('roundtrip stitches', back.stitches.length);
