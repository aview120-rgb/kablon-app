import fs from 'fs';
import { setFontLoader, textToShapes } from '../src/engine/text.js';
import { compileDesign, toPattern, designStats, centerObjects } from '../src/engine/design.js';
import { writeDST } from '../src/engine/formats.js';
setFontLoader(async (f) => fs.readFileSync('public/fonts/' + f));
const cases = [
  ['he', 'שלום אמא 2026', { font: 'rubik', heightMm: 15, bold: true }],
  ['mix', 'Mazal Tov מזל טוב\nمرحبا 你好 नमस्ते', { font: 'heebo', heightMm: 30 }],
  ['arc', 'לאמא היקרה', { font: 'secular', heightMm: 14, arc: 120 }],
];
for (const [n, t, o] of cases) {
  let s = Date.now();
  const r = await textToShapes(t, o);
  const objs = centerObjects([{ id: 'a', color: '#1d3fbb', shapes: r.shapes, stitch: { kind: 'auto' } }]);
  const comp = compileDesign(objs, {});
  console.log(n, 'ms', Date.now() - s, 'shapes', r.shapes.length, 'size', r.widthMm.toFixed(1), r.heightMm, designStats(comp).stitches);
  fs.writeFileSync(`/tmp/claude-0/out/${n}.dst`, writeDST(toPattern(comp)));
}
