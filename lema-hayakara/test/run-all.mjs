// Engine test-suite: run with `npm test`. Writes sample files to test/out for format validation.
import fs from 'fs';
import assert from 'assert/strict';
import { makeImage } from '../src/engine/raster.js';
import { digitizeImage, compileDesign, toPattern, designStats, centerObjects, patternToObjects, mergeSimilarColors, transformObjects, designBounds } from '../src/engine/design.js';
import { writeDST, writePES, writeJEF, writeEXP, readDST, readEXP, STITCH, COLOR, JUMP } from '../src/engine/formats.js';
import { setFontLoader, textToShapes } from '../src/engine/text.js';
import { photoLines } from '../src/engine/photo.js';

const OUT = new URL('./out/', import.meta.url).pathname;
fs.mkdirSync(OUT, { recursive: true });
setFontLoader(async (f) => fs.readFileSync(new URL('../public/fonts/' + f, import.meta.url)));
let passed = 0;
const test = async (name, fn) => {
  try { await fn(); passed++; console.log('✓', name); }
  catch (e) { console.error('✗', name, '\n ', e.stack); process.exitCode = 1; }
};
function logo() {
  const W = 400, H = 300, img = makeImage(W, H);
  for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) {
    let c = [255, 255, 255];
    const r = Math.hypot(x - 140, y - 150);
    if (r < 110) c = [220, 30, 40];
    if (r < 60) c = [255, 255, 255];
    if (r < 40) c = [20, 60, 200];
    if (x > 270 && x < 380 && y > 60 && y < 75) c = [20, 20, 20];
    if (Math.abs(x - 330) + Math.abs(y - 180) < 40) c = [30, 160, 60];
    img.data.set([...c, 255], (y * W + x) * 4);
  }
  return img;
}
const maxStep = (st) => { let m = 0; for (let i = 1; i < st.length; i++) if (st[i][2] === STITCH && st[i - 1][2] === STITCH) m = Math.max(m, Math.hypot(st[i][0] - st[i - 1][0], st[i][1] - st[i - 1][1])); return m; };

await test('logo digitizes into the right colours', () => {
  const d = digitizeImage(logo(), { widthMm: 100, colors: 5 });
  assert.equal(d.objects.length, 4, 'red, blue, black, green (white background skipped)');
  const c = compileDesign(centerObjects(d.objects));
  const s = designStats(c);
  assert.ok(s.stitches > 2000 && s.stitches < 8000, 'stitch count ' + s.stitches);
  assert.equal(s.colors, 4);
  assert.ok(s.longest <= 7.01, 'no stitch longer than 7mm');
  fs.writeFileSync(OUT + 'logo.dst', writeDST(toPattern(c, { name: 'logo' })));
  fs.writeFileSync(OUT + 'logo.pes', writePES({ ...toPattern(c), colors: c.blocks.map((b) => b.color) }));
  fs.writeFileSync(OUT + 'logo.jef', writeJEF({ ...toPattern(c), colors: c.blocks.map((b) => b.color) }));
  fs.writeFileSync(OUT + 'logo.exp', writeEXP(toPattern(c)));
});

await test('DST round trip keeps stitches, colours and size', () => {
  const d = digitizeImage(logo(), { widthMm: 80, colors: 5 });
  const c = compileDesign(centerObjects(d.objects));
  const pat = toPattern(c);
  const back = readDST(writeDST(pat));
  const n1 = pat.stitches.filter((s) => s[2] === STITCH).length;
  const n2 = back.stitches.filter((s) => s[2] === STITCH).length;
  assert.ok(Math.abs(n1 - n2) <= 2, `${n1} vs ${n2}`);
  assert.equal(back.stitches.filter((s) => s[2] === COLOR).length, c.blocks.length - 1);
  assert.ok(maxStep(back.stitches) <= 121, 'max DST step');
  const objs = patternToObjects(back, ['#000000']);
  assert.equal(objs.length, c.blocks.length);
});

await test('EXP round trip', () => {
  const c = compileDesign(centerObjects(digitizeImage(logo(), { widthMm: 60, colors: 4 }).objects));
  const back = readEXP(writeEXP(toPattern(c)));
  assert.ok(back.stitches.filter((s) => s[2] === STITCH).length > 1000);
});

await test('Hebrew, mixed scripts and emoji text', async () => {
  for (const [t, o] of [['שלום אמא', { font: 'rubik' }], ['Mazal Tov מזל טוב 123', { font: 'heebo' }], ['مرحبا 你好 नमस्ते Привет', { font: 'notosans' }], ['אמא ❤ 😀', { font: 'secular' }]]) {
    const r = await textToShapes(t, { ...o, heightMm: 12 });
    assert.ok(r.shapes.length >= 4, t + ' shapes ' + r.shapes.length);
    assert.ok(Math.abs(r.heightMm - 12) < 1.5, 'height ' + r.heightMm);
    const c = compileDesign([{ id: 't', color: '#000000', shapes: r.shapes, stitch: { kind: 'auto' } }]);
    assert.ok(designStats(c).stitches > 200);
  }
  const arc = await textToShapes('לאמא היקרה', { heightMm: 10, arc: 140 });
  assert.ok(arc.heightMm > 12, 'arc makes the block taller');
});

await test('photo line shading', () => {
  const W = 200, H = 150, img = makeImage(W, H);
  for (let i = 0; i < W * H; i++) { const v = (i % W) * 255 / W; img.data.set([v, v, v, 255], i * 4); }
  const p = photoLines(img, { widthMm: 80 });
  const c = compileDesign(centerObjects(p.objects));
  assert.ok(designStats(c).stitches > 1500);
  fs.writeFileSync(OUT + 'photo.dst', writeDST(toPattern(c)));
});

await test('merge colours to 12 needles and scaling', () => {
  const objs = Array.from({ length: 20 }, (_, i) => ({ id: 'o' + i, color: '#' + ((i * 0x0c0a05 + 0x101010) & 0xffffff).toString(16).padStart(6, '0'), shapes: [{ outer: [{ x: i * 5, y: 0 }, { x: i * 5 + 4, y: 0 }, { x: i * 5 + 4, y: 4 }, { x: i * 5, y: 4 }], holes: [] }] }));
  const m = mergeSimilarColors(objs, 12);
  assert.equal(new Set(m.map((o) => o.color)).size, 12);
  const b0 = designBounds(objs), b1 = designBounds(transformObjects(objs, { scale: 2 }));
  assert.ok(Math.abs(b1.w - b0.w * 2) < 1e-6);
});

console.log(`\n${passed} tests passed`);
