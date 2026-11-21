// Engine test-suite: run with `npm test`. Writes sample files to test/out for format validation.
import fs from 'fs';
import assert from 'assert/strict';
import { fileURLToPath } from 'url';
import { makeImage } from '../src/engine/raster.js';
import { digitizeImage, compileDesign, toPattern, designStats, centerObjects, patternToObjects, mergeSimilarColors, transformObjects, designBounds } from '../src/engine/design.js';
import { writeDST, writePES, writeJEF, writeEXP, readDST, readEXP, STITCH, COLOR, JUMP } from '../src/engine/formats.js';
import { setFontLoader, textToShapes } from '../src/engine/text.js';
import { photoLines } from '../src/engine/photo.js';

import { readPES, readJEF } from '../src/engine/formats.js';
import { LIBRARY, libraryShapes, outlineBand } from '../src/engine/library.js';
import { makeBackground } from '../src/engine/background.js';
import { satinShape } from '../src/engine/satin.js';
import { rasterizeRings } from '../src/engine/vector.js';
const OUT = fileURLToPath(new URL('./out/', import.meta.url));
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


// ---- new features ----

await test('turning satin: one continuous column per stroke, full coverage', async () => {
  const r = await textToShapes('ל', { font: 'rubik', heightMm: 25 });
  const s = r.shapes[0];
  const sat = satinShape(s, { satinSpacing: 0.4, pullComp: 0.2 });
  assert.ok(sat && sat.runs.length >= 1 && sat.runs.length <= 3, 'columns ' + (sat && sat.runs.length));
  const c = compileDesign([{ id: 't', color: '#000000', shapes: r.shapes, stitch: { kind: 'auto' } }]);
  const st = designStats(c);
  assert.ok(st.longest < 7.1, 'no over-long stitch');
  assert.ok(st.stitches > 150, "stitches " + st.stitches);
});

await test('PES / JEF round trip keeps every colour step', () => {
  const c = compileDesign(centerObjects(digitizeImage(logo(), { widthMm: 60, colors: 5 }).objects));
  const pat = { ...toPattern(c), colors: c.blocks.map((b) => b.color) };
  for (const [w, r] of [[writePES, readPES], [writeJEF, readJEF]]) {
    const back = r(w(pat));
    assert.equal(back.stitches.filter((s) => s[2] === COLOR).length, c.blocks.length - 1);
    assert.ok(back.stitches.filter((s) => s[2] === STITCH).length > 1000);
  }
});

await test('shapes library: every shape stitches', () => {
  for (const it of LIBRARY) {
    const sh = libraryShapes(it.id, 30);
    assert.ok(sh.length >= 1, it.id);
    const c = compileDesign([{ id: it.id, color: '#aa0000', shapes: sh, stitch: { kind: 'auto' } }]);
    assert.ok(designStats(c).stitches > 100, it.id);
  }
});

await test('appliqué makes three steps with stops, outline band has no stray frame', () => {
  const sh = libraryShapes('heart', 60);
  const c = compileDesign([{ id: 'a', color: '#2266aa', shapes: sh, applique: { width: 3 } }]);
  assert.equal(c.blocks.length, 3);
  const band = outlineBand(sh, 3, 0.6);
  const b = designBounds([{ shapes: band }]);
  const h = designBounds([{ shapes: sh }]);
  assert.ok(b.w < h.w + 4, 'band hugs the heart (no frame around the image)');
});

await test('background with knock-out and border', async () => {
  const r = await textToShapes('טלית', { font: 'frank', heightMm: 20, weight: 900 });
  const t = { id: 't', color: '#1a2a6c', shapes: r.shapes, stitch: { kind: 'auto' } };
  const bg = makeBackground([t], { shape: 'rect', margin: 6, color: '#d9c7a0', border: true, borderColor: '#1f5f8b', borderWidth: 3.5 });
  assert.equal(bg.length, 2);
  const c = compileDesign([...bg, t]);
  assert.equal(c.blocks.length, 3);
  fs.writeFileSync(OUT + 'talit.dst', writeDST(toPattern(c)));
});

await test('text shapes: arc, circle, wave, vertical, slant – and real STaM font', async () => {
  const flat = await textToShapes('לאמא היקרה', { font: 'rubik', heightMm: 15 });
  for (const o of [{ shape: 'arc', arc: 120 }, { shape: 'circle', circleDiameter: 80 }, { shape: 'circle', circleDiameter: 80, circleBottom: true }, { shape: 'wave', waveHeight: 50, waveCount: 1.5 }, { shape: 'vertical' }, { slant: 15 }]) {
    const r = await textToShapes('לאמא היקרה', { font: 'rubik', heightMm: 15, ...o });
    assert.ok(r.shapes.length >= 8, JSON.stringify(o) + ' letters ' + r.shapes.length);
    const c = compileDesign([{ id: 't', color: '#000', shapes: r.shapes, stitch: { kind: 'auto' } }]);
    assert.ok(designStats(c).longest < 7.1);
    if (o.shape === 'vertical') assert.ok(r.heightMm > flat.heightMm * 4 && r.widthMm < flat.widthMm / 3, 'vertical is tall and narrow');
    if (o.shape === 'circle') assert.ok(r.heightMm > flat.heightMm * 1.3, 'circle bends the text');
  }
  const stam = await textToShapes('טלית', { font: 'stamashk', heightMm: 25 });
  assert.equal(stam.shapes.length, 4);
  // vertical stitches: every stitch of a forced 90° fill runs up/down
  const box = [{ outer: [{ x: 0, y: 0 }, { x: 30, y: 0 }, { x: 30, y: 20 }, { x: 0, y: 20 }], holes: [], width: 20 }];
  const cv = compileDesign([{ id: 'b', color: '#000', shapes: box, stitch: { kind: 'fill', angle: Math.PI / 2 } }]);
  let sx = 0, sy = 0;
  const pts = cv.blocks[0].points;
  for (let i = 1; i < pts.length; i++) { if (pts[i].j) continue; const dx = Math.abs(pts[i].x - pts[i - 1].x), dy = Math.abs(pts[i].y - pts[i - 1].y); if (dx + dy > 1) { sx += dx; sy += dy; } }
  assert.ok(sy > sx * 3, 'stitches run top to bottom (dy ' + sy.toFixed(0) + ' dx ' + sx.toFixed(0) + ')');
});

await test('logo with thin gold text on white keeps every letter in one colour', async () => {
  // anti-aliased thin letters (like a printed logo): rasterise 4x, then average down
  const t = await textToShapes('Heritage Foundation', { font: 'opensans', heightMm: 6 });
  const res = 0.1; // mm per final pixel
  const { mask, w, h } = rasterizeRings(t.shapes.flatMap((sh) => [sh.outer, ...sh.holes]), res / 4, 16, 'evenodd');
  const W = Math.floor(w / 4), H = Math.floor(h / 4);
  const img = makeImage(W, H);
  const gold = [186, 140, 40];
  for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) {
    let c = 0; for (let dy = 0; dy < 4; dy++) for (let dx = 0; dx < 4; dx++) c += mask[(y * 4 + dy) * w + x * 4 + dx];
    const a = c / 16, o = (y * W + x) * 4;
    for (let k = 0; k < 3; k++) img.data[o + k] = Math.round(255 * (1 - a) + gold[k] * a);
    img.data[o + 3] = 255;
  }
  const d = digitizeImage(img, { widthMm: W * res, colors: 3, removeBackground: true, detail: 0.5, mode: 'logo' });
  assert.equal(d.objects.length, 1, 'one thread colour, got ' + d.objects.map((o) => o.color).join(','));
  const letters = t.shapes.length;
  const got = d.objects[0].shapes.length;
  assert.ok(got >= letters - 1, `letters kept: ${got} of ${letters}`);
});

console.log(`\n${passed} tests passed`);