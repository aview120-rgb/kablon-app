// Text shapes, live preview, stitch direction, moving with the arrow pad.
const { chromium } = require('playwright');
const out = process.argv[2] || '/tmp/claude-0/shots-text';
require('fs').mkdirSync(out, { recursive: true });
(async () => {
  const b = await chromium.launch();
  const ctx = await b.newContext({ viewport: { width: 412, height: 860 }, deviceScaleFactor: 1 });
  const p = await ctx.newPage();
  const errors = [];
  p.on('pageerror', (e) => errors.push('pageerror: ' + e.message));
  p.on('console', (m) => { if (m.type() === 'error') errors.push('console: ' + m.text()); });
  await p.goto('http://localhost:4173/');
  await p.waitForSelector('.big');
  await p.click('[data-start=text]');
  await p.waitForSelector('#tv');
  await p.fill('#tv', 'לאמא היקרה');
  await p.fill('#th', '1.5');
  // pick a real STaM font
  await p.click('#fCats button:has-text("סת״ם")');
  await p.click('.font-card[data-f=stamashk]');
  // circle shape with live preview
  await p.click('#tShape [data-s=circle]');
  await p.waitForSelector('#circD');
  for (let i = 0; i < 4; i++) await p.click('[data-step=circD][data-d="1"]');
  await p.waitForTimeout(600);
  const info1 = await p.textContent('#tPrevInfo');
  console.log('circle preview', info1, 'diameter', await p.inputValue('#circD'));
  if (!/ס״מ/.test(info1)) throw new Error('no live preview');
  await p.screenshot({ path: out + '/t1-circle-dialog.png', fullPage: false });
  // wave
  await p.click('#tShape [data-s=wave]');
  await p.waitForSelector('#wH');
  await p.waitForTimeout(500);
  await p.locator('#tPrev').scrollIntoViewIfNeeded();
  await p.screenshot({ path: out + '/t2-wave-dialog.png' });
  // back to circle, vertical stitches
  await p.click('#tShape [data-s=circle]');
  await p.click('#tDir [data-d=v]');
  await p.click('#ok');
  await p.waitForFunction(() => window.__lema.compiled && window.__lema.compiled.blocks.length > 0 && window.__lema.project.objects.length, null, { timeout: 60000 });
  await p.waitForTimeout(800);
  const st = await p.evaluate(() => { const o = window.__lema.project.objects[0]; return { shape: o.text.shape, angle: o.stitch.angle, font: o.text.font }; });
  console.log('object', st);
  if (st.shape !== 'circle' || Math.abs(st.angle - Math.PI / 2) > 1e-6) throw new Error('shape/direction not saved');
  await p.screenshot({ path: out + '/t3-editor-circle.png' });
  // drag a letter directly (no tap first)
  await p.click('#btnSelect');
  const scr = await p.evaluate(() => {
    const o = window.__lema.project.objects[0];
    const pv = window.__lema.preview;
    const inside = (x, y, r) => { let c = false; for (let i = 0, j = r.length - 1; i < r.length; j = i++) { const a = r[i], b = r[j]; if ((a.y > y) !== (b.y > y) && x < ((b.x - a.x) * (y - a.y)) / (b.y - a.y) + a.x) c = !c; } return c; };
    const sh = o.shapes.reduce((a, b) => (b.outer.length > a.outer.length ? b : a));
    let x0 = 1e9, x1 = -1e9, y0 = 1e9, y1 = -1e9; for (const q of sh.outer) { x0 = Math.min(x0, q.x); x1 = Math.max(x1, q.x); y0 = Math.min(y0, q.y); y1 = Math.max(y1, q.y); }
    let best = null;
    for (let i = 1; i < 40 && !best; i++) for (let j = 1; j < 40; j++) { const x = x0 + ((x1 - x0) * i) / 40, y = y0 + ((y1 - y0) * j) / 40; if (inside(x, y, sh.outer) && !sh.holes.some((h) => inside(x, y, h)) && inside(x + 0.3, y, sh.outer) && inside(x - 0.3, y, sh.outer) && inside(x, y + 0.3, sh.outer) && inside(x, y - 0.3, sh.outer)) { best = { x, y }; break; } }
    const r = pv.c.getBoundingClientRect();
    return { x: r.left + r.width / 2 + pv.view.ox + best.x * pv.view.scale, y: r.top + r.height / 2 + pv.view.oy + best.y * pv.view.scale, scale: pv.view.scale };
  });
  const cy0 = await p.evaluate(() => { const o = window.__lema.project.objects[0]; let y = 0, n = 0; for (const s of o.shapes) for (const q of s.outer) { y += q.y; n++; } return y / n; });
  await p.mouse.move(scr.x, scr.y);
  await p.mouse.down();
  for (let i = 1; i <= 10; i++) await p.mouse.move(scr.x, scr.y - i * 2);
  await p.screenshot({ path: out + '/t3b-dragging.png' });
  await p.mouse.up();
  await p.waitForTimeout(800);
  const cy1 = await p.evaluate(() => { const o = window.__lema.project.objects[0]; let y = 0, n = 0; for (const s of o.shapes) for (const q of s.outer) { y += q.y; n++; } return y / n; });
  console.log('drag moved', (cy1 - cy0).toFixed(2), 'mm, expected', (-20 / scr.scale).toFixed(2));
  if (Math.abs(cy1 - cy0 + 20 / scr.scale) > 0.2) throw new Error('direct drag failed');
  await p.click('#btnSelect');
  // select + arrow pad
  const box = await p.locator('#stage canvas, canvas#preview, .stage canvas').first().boundingBox();
  const before = await p.evaluate(() => { const o = window.__lema.project.objects[0]; let y = 0, n = 0; for (const s of o.shapes) for (const q of s.outer) { y += q.y; n++; } return y / n; });
  // tap on a letter: find a point inside the shapes from the app itself
  const pt = await p.evaluate(() => { const o = window.__lema.project.objects[0]; const s = o.shapes.reduce((a, b) => (b.outer.length > a.outer.length ? b : a)); const pv = window.__lema.preview; return { mm: s.outer[0], view: { ...pv.view } }; });
  void box; void pt;
  await p.evaluate(() => window.__lema.selectFirst && window.__lema.selectFirst());
  await p.waitForSelector('#selBar:not([hidden])', { timeout: 5000 });
  await p.click('#ndStep [data-st="5"]');
  await p.click('[data-nd="0,1"]');
  await p.click('[data-nd="0,1"]');
  await p.waitForTimeout(900);
  const after = await p.evaluate(() => { const o = window.__lema.project.objects[0]; let y = 0, n = 0; for (const s of o.shapes) for (const q of s.outer) { y += q.y; n++; } return y / n; });
  console.log('moved down by', (after - before).toFixed(2), 'mm');
  if (Math.abs(after - before - 10) > 0.01) throw new Error('arrow pad did not move 10mm');
  console.log('pos', await p.textContent('#selPos'));
  await p.screenshot({ path: out + '/t4-moved.png' });
  await p.click('[data-sa=center]');
  await p.waitForTimeout(800);
  const c = await p.evaluate(() => { const o = window.__lema.project.objects[0]; let x0 = 1e9, x1 = -1e9, y0 = 1e9, y1 = -1e9; for (const s of o.shapes) for (const q of s.outer) { x0 = Math.min(x0, q.x); x1 = Math.max(x1, q.x); y0 = Math.min(y0, q.y); y1 = Math.max(y1, q.y); } return [(x0 + x1) / 2, (y0 + y1) / 2]; });
  console.log('after centre', c.map((v) => v.toFixed(2)));
  if (Math.abs(c[0]) > 0.01 || Math.abs(c[1]) > 0.01) throw new Error('centre failed');
  // stitch direction in the stitch tab
  await p.click('[data-sa=close]');
  await p.click('#btnSelect');
  await p.click('[data-tab=stitch]');
  await p.selectOption('[data-dirsel]', 'h');
  await p.waitForTimeout(800);
  console.log('angle after ↔', await p.evaluate(() => window.__lema.project.objects[0].stitch.angle));
  await p.screenshot({ path: out + '/t5-stitch-tab.png' });
  console.log('errors', errors);
  await b.close();
  if (errors.length) process.exit(1);
})().catch((e) => { console.error(e); process.exit(1); });
