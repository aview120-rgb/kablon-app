import fs from 'fs';
import { setFontLoader, textToShapes, FONTS } from '../src/engine/text.js';
import { compileDesign, designStats } from '../src/engine/design.js';
setFontLoader(async (f) => fs.readFileSync(new URL('../public/fonts/' + f, import.meta.url)));
let bad = 0;
const jobs = [];
for (const f of FONTS) {
  if (f.files) for (const w of Object.keys(f.files)) jobs.push([f, +w]);
  else jobs.push([f, undefined]);
}
for (const [f, weight] of jobs) {
  const sample = f.cat.startsWith('he') ? 'שלום אמא' : f.cat === 'other' ? (f.id === 'naskh' || f.id === 'cairo' || f.id === 'amiri' ? 'مرحبا' : '你好') : 'Hello Mom';
  try {
    const t = Date.now();
    const r = await textToShapes(sample, { font: f.id, heightMm: 12, weight });
    const c = compileDesign([{ id: 'x', color: '#000000', shapes: r.shapes, stitch: { kind: 'auto' } }]);
    const st = designStats(c).stitches;
    if (!r.shapes.length || st < 100) { bad++; console.log('EMPTY', f.id, weight || ''); }
    else console.log('ok', (f.id + (weight ? '@' + weight : '')).padEnd(16), String(r.shapes.length).padStart(3), 'shapes', String(st).padStart(6), 'st', Date.now() - t, 'ms');
  } catch (e) { bad++; console.log('FAIL', f.id, e.message); }
}
console.log(FONTS.length, 'fonts,', bad, 'problems');
if (bad) process.exitCode = 1;
