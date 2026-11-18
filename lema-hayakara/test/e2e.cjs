const { chromium } = require('playwright');
const path = require('path');
const out = process.argv[2] || '/tmp/claude-0/shots';
require('fs').mkdirSync(out, { recursive: true });
(async () => {
  const b = await chromium.launch();
  const ctx = await b.newContext({ viewport: { width: 412, height: 860 }, deviceScaleFactor: 1, acceptDownloads: true });
  const p = await ctx.newPage();
  const errors = [];
  p.on('pageerror', (e) => errors.push('pageerror: ' + e.message));
  p.on('console', (m) => { if (m.type() === 'error') errors.push('console: ' + m.text()); });
  await p.goto('http://localhost:4173/');
  await p.waitForSelector('.big');
  await p.screenshot({ path: out + '/01-home.png' });
  // ---- logo flow
  const [fc] = await Promise.all([p.waitForEvent('filechooser'), p.click('[data-start=image]')]);
  await fc.setFiles('/tmp/claude-0/logo.png');
  await p.waitForSelector('#go', { timeout: 20000 });
  await p.screenshot({ path: out + '/02-image-dialog.png' });
  await p.click('#go');
  await p.waitForFunction(() => window.__lema.compiled && window.__lema.compiled.blocks.length > 0, null, { timeout: 60000 });
  await p.waitForTimeout(500);
  await p.screenshot({ path: out + '/03-editor.png' });
  const w1 = await p.evaluate(() => window.__lema.warnings.map((w) => w.level + ':' + w.text));
  console.log('logo warnings', w1);
  await p.click('[data-tab=colors]');
  await p.waitForTimeout(300);
  await p.screenshot({ path: out + '/04-colors.png' });
  // add text
  await p.click('[data-tab=text]');
  await p.click('#addText');
  await p.fill('#tv', 'שלום אמא ❤ Mom');
  await p.fill('#th', '1.2');
  await p.click('#ok');
  await p.waitForFunction(() => window.__lema.project.objects.some((o) => o.type === 'text'), null, { timeout: 30000 });
  await p.waitForTimeout(1500);
  await p.screenshot({ path: out + '/05-text.png' });
  console.log('after text warnings', await p.evaluate(() => window.__lema.warnings.map((w) => w.level + ':' + (w.fix || ''))));
  // fit hoop if needed
  const fix = await p.$('[data-fix]');
  if (fix) { await fix.click(); await p.waitForTimeout(1500); }
  // export
  await p.click('#btnExport');
  await p.waitForSelector('#save');
  await p.screenshot({ path: out + '/06-export.png' });
  const [dl] = await Promise.all([p.waitForEvent('download'), p.click('#save')]);
  await dl.saveAs(out + '/out.dst');
  console.log('downloaded', dl.suggestedFilename());
  await p.click('#sheet');
  await p.waitForSelector('#ws');
  await p.screenshot({ path: out + '/07-sheet.png' });
  const [dl2] = await Promise.all([p.waitForEvent('download'), p.click('#ws')]);
  await dl2.saveAs(out + '/sheet.png');
  await p.click('#wc');
  // sim
  await p.click('[data-tab=view]');
  await p.click('#sim');
  await p.waitForTimeout(800);
  await p.screenshot({ path: out + '/08-sim.png' });
  await p.click('#simClose');
  // back home -> recent
  await p.click('#btnBack');
  await p.waitForTimeout(800);
  await p.screenshot({ path: out + '/09-home-recent.png' });
  // ---- photo lines flow
  const [fc2] = await Promise.all([p.waitForEvent('filechooser'), p.click('[data-start=photo]')]);
  await fc2.setFiles('/tmp/claude-0/photo.jpg');
  await p.waitForSelector('#go');
  await p.click('[data-m=lines]');
  await p.click('[data-a=none]');
  await p.click('#go');
  await p.waitForFunction(() => window.__lema.compiled && window.__lema.compiled.blocks.length > 0 && window.__lema.project.source.mode === 'lines', null, { timeout: 60000 });
  await p.waitForTimeout(500);
  await p.screenshot({ path: out + '/10-photo-lines.png' });
  // ---- open DST
  await p.click('#btnBack');
  await p.waitForTimeout(500);
  const [fc3] = await Promise.all([p.waitForEvent('filechooser'), p.click('[data-start=open]')]);
  await fc3.setFiles(out + '/out.dst');
  await p.waitForFunction(() => window.__lema.project.objects.some((o) => o.imported), null, { timeout: 30000 });
  await p.waitForTimeout(800);
  await p.screenshot({ path: out + '/11-open-dst.png' });
  console.log('errors', errors);
  await b.close();
})();
