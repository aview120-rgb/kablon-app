import './styles.css';
import { call, onProgress } from './app/engine.js';
import { Preview, thumbnail } from './app/render.js';
import { analyze, compiledBounds, compiledStats, NEEDLES } from './app/warnings.js';
import { HOOPS, hoopById, HOOP_MARGIN } from './app/hoops.js';
import { colorName } from './app/colornames.js';
import { renderWorksheet } from './app/worksheet.js';
import { importFile, dataUrlToImage } from './app/importer.js';
import { saveFile, saveMany, shareFile, canShare, checkForUpdate, installUpdate, onNativeUpdateEvent, APP_VERSION, platform } from './app/platform.js';
import { saveProject, getProject, listProjects, deleteProject } from './app/store.js';
import { transformObjects, mergeSimilarColors, designBounds, FABRICS, toPattern, newId } from './engine/design.js';
import { FORMATS } from './engine/formats.js';
import { FONTS, FONT_CATS } from './engine/text.js';
import { pointInShape } from './engine/geom.js';
import { LIBRARY, librarySvgPath } from './engine/library.js';

const $ = (s, el = document) => el.querySelector(s);
const $$ = (s, el = document) => [...el.querySelectorAll(s)];
const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const DEFAULT_PALETTE = ['#1b1b1b', '#c62828', '#1565c0', '#2e7d32', '#f9a825', '#6a1b9a', '#ef6c00', '#00838f', '#ad1457', '#4e342e', '#9e9e9e', '#ffffff'];

// ---------------- state ----------------
let P = null;          // current project
let compiled = null;   // packed compiled blocks
let warnings = [];
let currentTab = 'size';
let compileTimer = 0;
let compileSeq = 0;
let saveTimer = 0;
let undoOwner = null;
const preview = new Preview($('#canvas'));

function newProject(name = 'עיצוב חדש') {
  return {
    id: 'p' + Date.now().toString(36) + Math.random().toString(36).slice(2, 6),
    name, created: Date.now(), updated: Date.now(),
    source: null, objects: [],
    settings: { hoop: 'r150', customHoop: { w: 150, h: 150 }, fabric: 'cotton', densityFactor: 1, underlay: true },
    needles: {}, threadNames: {}, view: { fabric: '#f4f1ea', realistic: true },
  };
}
const hoop = () => hoopById(P.settings.hoop, P.settings.customHoop);

// ---------------- helpers ----------------
function toast(msg, ms = 2600) {
  const t = $('#toast');
  t.textContent = msg; t.hidden = false;
  clearTimeout(toast._t);
  toast._t = setTimeout(() => (t.hidden = true), ms);
}
function busy(on, text = 'עובד…') { $('#busy').hidden = !on; $('#busyText').textContent = text; }
onProgress((m) => { $('#busyText').textContent = m; });

function modal(html, onMount) {
  $('#modalBody').innerHTML = html;
  $('#modal').hidden = false;
  const close = () => { $('#modal').hidden = true; $('#modalBody').innerHTML = ''; };
  onMount?.($('#modalBody'), close);
  return close;
}
$('#modal').addEventListener('click', (e) => { if (e.target.id === 'modal') { $('#modal').hidden = true; } });

function confirmBox(text, okLabel = 'אישור') {
  return new Promise((res) => {
    modal(`<h2>${esc(text)}</h2><div class="modal-actions"><button class="btn" data-ok>${esc(okLabel)}</button><button class="btn ghost" data-no>ביטול</button></div>`, (el, close) => {
      $('[data-ok]', el).onclick = () => { close(); res(true); };
      $('[data-no]', el).onclick = () => { close(); res(false); };
    });
  });
}

function pickFile(input) {
  return new Promise((res) => {
    input.value = '';
    input.onchange = () => res(input.files[0] || null);
    input.click();
  });
}

function show(view) { $$('.view').forEach((v) => v.classList.toggle('active', v.id === view)); if (view === 'editor') { setTimeout(() => { preview.fit(); }, 30); } }

function uniqueColors() {
  const seen = [];
  for (const o of P.objects) if (o.visible !== false && !seen.includes(o.color.toLowerCase())) seen.push(o.color.toLowerCase());
  return seen;
}

function assignNeedles() {
  const used = new Set();
  const cols = uniqueColors();
  for (const c of cols) if (P.needles[c]) used.add(P.needles[c]);
  let n = 1;
  for (const c of cols) {
    if (P.needles[c]) continue;
    while (used.has(n) && n < NEEDLES) n++;
    P.needles[c] = used.has(n) ? ((cols.indexOf(c) % NEEDLES) + 1) : n;
    used.add(P.needles[c]);
  }
}

// ---------------- compile / render ----------------
function scheduleCompile(delay = 120) {
  clearTimeout(compileTimer);
  compileTimer = setTimeout(runCompile, delay);
}

async function runCompile() {
  const seq = ++compileSeq;
  assignNeedles();
  const objs = P.objects.filter((o) => o.visible !== false);
  try {
    const res = await call('compile', { objects: objs, settings: P.settings });
    if (seq !== compileSeq) return;
    compiled = res;
    preview.fabric = P.view.fabric;
    preview.realistic = P.view.realistic;
    if (preview.selection) preview.selection.fill = null; // stitches are up to date again
    preview.setData(compiled, hoop());
    warnings = analyze({ compiled, objects: P.objects, settings: P.settings, hoop: hoop(), uniqueColors: uniqueColors().length });
    renderWarnings();
    renderInfo();
    if (['colors', 'size'].includes(currentTab)) renderPanel();
    if (undoOwner !== P) { undoOwner = P; resetUndo(); }
    trackUndo();
    scheduleSave();
  } catch (e) {
    console.error(e);
    toast('שגיאה בחישוב הרקמה: ' + e.message);
  }
}

function renderInfo() {
  if (!compiled) { $('#stageInfo').textContent = ''; return; }
  const b = compiledBounds(compiled), st = compiledStats(compiled);
  $('#stageInfo').innerHTML = `רוחב ${(b.w / 10).toFixed(1)} · גובה ${(b.h / 10).toFixed(1)} ס״מ · ${st.stitches.toLocaleString('he-IL')} תפרים · ${compiled.blocks.length} שלבים · כ-${Math.max(1, Math.round(st.minutes))} דק׳`;
}

function renderWarnings() {
  const el = $('#warnings');
  const icon = { error: '⛔', warn: '⚠️', info: 'ℹ️', ok: '✅' };
  el.innerHTML = warnings.map((w, i) => `<div class="warn-item ${w.level}"><span class="dot">${icon[w.level]}</span><p>${esc(w.text)}</p>${w.fix ? `<button data-fix="${i}">${esc(w.fixLabel)}</button>` : ''}</div>`).join('');
  $$('[data-fix]', el).forEach((b) => (b.onclick = () => applyFix(warnings[+b.dataset.fix].fix)));
}

function fitScaleForHoop() {
  if (!compiled) return 1;
  const h = hoop();
  const b = compiledBounds(compiled);
  if (h.shape === 'round') {
    let maxR = 0;
    for (const blk of compiled.blocks) for (let i = 0; i < blk.xy.length; i += 2) maxR = Math.max(maxR, Math.hypot(blk.xy[i], blk.xy[i + 1]));
    return ((h.w / 2 - HOOP_MARGIN) / Math.max(1e-6, maxR)) * 0.98;
  }
  return Math.min((h.w - 2 * HOOP_MARGIN) / Math.max(1e-6, b.w), (h.h - 2 * HOOP_MARGIN) / Math.max(1e-6, b.h)) * 0.98;
}

async function applyFix(fix) {
  if (fix === 'mergeColors') { P.objects = mergeSimilarColors(P.objects, NEEDLES); toast('הצבעים מוזגו ל-12'); }
  else if (fix === 'fitHoop') { await scaleDesign(fitScaleForHoop()); toast('הגודל הותאם לחישוק'); return; }
  else if (fix === 'enlarge') { const b = designBounds(P.objects); await scaleDesign(30 / Math.max(b.w, b.h, 1)); return; }
  else if (fix === 'densityNormal') { P.settings.densityFactor = 1; }
  else if (fix.startsWith('textMin:')) { const o = P.objects.find((x) => x.id === fix.slice(8)); if (o) { const h = o.text.heightMm || 6; pushUndo(); await regenerateText(o, { ...o.text, heightMm: h < 6 ? 6 : h * 1.4 }); } }
  else if (fix.startsWith('resetImportScale:')) { const o = P.objects.find((x) => x.id === fix.slice(17)); if (o && o.scaled) { const k = 1 / o.scaled; const [t] = transformObjects([o], { scale: k }); Object.assign(o, t, { scaled: 1 }); } }
  renderPanel();
  scheduleCompile(0);
}

// Scale the whole design. Photo-line objects are regenerated at the new size.
async function scaleDesign(k) {
  if (!isFinite(k) || k <= 0 || Math.abs(k - 1) < 1e-4) return;
  const b = designBounds(P.objects);
  const cx = (b.minX + b.maxX) / 2, cy = (b.minY + b.maxY) / 2;
  const scaledAll = transformObjects(P.objects, { scale: k });
  P.objects = scaledAll.map((o, i) => {
    const orig = P.objects[i];
    if (orig.type === 'text' && orig.text) o.text = { ...orig.text, heightMm: orig.text.heightMm * k };
    if (orig.imported) o.scaled = (orig.scaled || 1) * k;
    return o;
  });
  void cx; void cy;
  if (P.source && P.source.mode === 'lines') {
    P.source.opts.widthMm *= k;
    await redigitize(false);
    return;
  }
  if (P.source) P.source.opts.widthMm *= k;
  renderPanel();
  scheduleCompile(0);
}

function scheduleSave() {
  clearTimeout(saveTimer);
  saveTimer = setTimeout(async () => {
    if (!P) return;
    P.updated = Date.now();
    try {
      if (compiled) P.thumb = thumbnail(compiled, 220, '#f7f5f0').toDataURL('image/png');
      await saveProject(JSON.parse(JSON.stringify(P)));
    } catch (e) { console.warn('save failed', e); }
  }, 800);
}

// ---------------- digitizing ----------------
async function getSourceImage() {
  const url = P.source.aiImage || P.source.preview;
  const { img } = await dataUrlToImage(url);
  return img;
}

async function redigitize(keepColors = true) {
  if (!P.source) return;
  busy(true, 'ממיר את התמונה לרקמה…');
  try {
    const s = P.source;
    const img = await getSourceImage();
    const oldColors = P.objects.filter((o) => o.fromSource).map((o) => o.color);
    let res;
    if (s.mode === 'lines') {
      res = await call('photo', { img, opts: { widthMm: s.opts.widthMm, rowSpacing: s.opts.rowSpacing || 1.1, angle: s.opts.angle || 0, contrast: s.opts.contrast || 1.2, invert: !!s.opts.invert, color: s.opts.lineColor || '#202020' } }, []);
    } else {
      res = await call('digitize', { img, opts: { widthMm: s.opts.widthMm, colors: s.opts.colors, detail: s.opts.detail, removeBackground: s.opts.skipBg !== false, mode: s.mode === 'photo' ? 'photo' : 'logo', maxColors: NEEDLES } }, []);
    }
    res.objects.forEach((o, i) => { o.fromSource = true; o.name = s.mode === 'lines' ? 'צילום בקווים' : 'צבע ' + (i + 1); });
    // keep the position of text objects relative to the new design
    const others = P.objects.filter((o) => !o.fromSource);
    P.objects = [...res.objects, ...others];
    void keepColors; void oldColors;
    renderPanel();
    await runCompile();
  } catch (e) {
    console.error(e);
    toast('לא הצלחנו להמיר: ' + e.message, 4000);
  } finally { busy(false); }
}

async function runAiBackground(kind) {
  busy(true, kind === 'quality' ? 'הבינה המלאכותית מסירה את הרקע (פעמיים לדיוק)… בטלפון זה יכול לקחת עד 2 דקות' : 'הבינה המלאכותית מסירה את הרקע… (בטלפון זה יכול לקחת עד דקה)');
  try {
    const { img } = await dataUrlToImage(P.source.preview);
    const out = await call('removeBg', { img, kind }, [img.data.buffer]);
    // crop to the object so the chosen size refers to the object, not the old background
    let x0 = out.width, y0 = out.height, x1 = -1, y1 = -1;
    for (let y = 0; y < out.height; y++) for (let x = 0; x < out.width; x++) {
      if (out.data[(y * out.width + x) * 4 + 3] > 0) { if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y; }
    }
    if (x1 < 0) { toast('לא נמצא אובייקט בתמונה – נסו בלי הסרת רקע', 4000); return; }
    const pad = Math.round(Math.max(out.width, out.height) * 0.01);
    x0 = Math.max(0, x0 - pad); y0 = Math.max(0, y0 - pad); x1 = Math.min(out.width - 1, x1 + pad); y1 = Math.min(out.height - 1, y1 + pad);
    const full = document.createElement('canvas');
    full.width = out.width; full.height = out.height;
    full.getContext('2d').putImageData(new ImageData(new Uint8ClampedArray(out.data), out.width, out.height), 0, 0);
    const c = document.createElement('canvas');
    c.width = x1 - x0 + 1; c.height = y1 - y0 + 1;
    c.getContext('2d').drawImage(full, x0, y0, c.width, c.height, 0, 0, c.width, c.height);
    P.source.aiImage = c.toDataURL('image/png');
    P.source.aiCrop = { x: x0, y: y0 };
    P.source.opts.ai = kind;
    toast('הרקע הוסר ✓');
  } catch (e) {
    console.error(e);
    toast('הסרת הרקע נכשלה: ' + e.message, 4000);
  } finally { busy(false); }
}

// ---------------- start flows ----------------
async function startImage(photo = false) {
  const f = await pickFile($('#fileImage'));
  if (!f) return;
  busy(true, 'פותח את הקובץ…');
  show('editor');
  let imp;
  try { imp = await importFile(f); } catch (e) { busy(false); show('home'); toast(e.message, 4500); return; }
  busy(false);
  if (imp.kind === 'embroidery') { await openEmbroidery(imp); return; }
  imageSetupDialog(imp, photo);
}

function imageSetupDialog(imp, photo) {
  let mode = photo ? 'photo' : 'logo';
  let colors = photo ? 8 : 5;
  let widthCm = 10;
  let ai = photo ? 'quality' : 'none';
  const aspect = imp.img.height / imp.img.width;
  const html = () => `
    <h2>${photo ? 'תמונת צילום' : 'תמונה / לוגו'}</h2>
    <img class="preview-img" src="${imp.preview}" alt="" />
    <div class="field"><div class="label">מה יש בתמונה?</div><div class="choice" id="modeChoice">
      <button data-m="logo" class="${mode === 'logo' ? 'on' : ''}"><b>לוגו / ציור / כיתוב</b><small>צבעים ברורים וקווים חדים</small></button>
      <button data-m="photo" class="${mode === 'photo' ? 'on' : ''}"><b>צילום צבעוני</b><small>אנשים, נופים, חיות – בכמה צבעים</small></button>
      <button data-m="lines" class="${mode === 'lines' ? 'on' : ''}"><b>צילום בקווים – צבע אחד</b><small>מראה אומנותי של הצללה בחוט אחד</small></button>
    </div></div>
    <div class="field" id="colorsField" ${mode === 'lines' ? 'hidden' : ''}><label>מספר צבעים: <b id="colorsVal">${colors}</b></label><input type="range" id="colors" min="1" max="12" value="${colors}" /><div class="hint">עד 12 – כמספר המחטים במכונה</div></div>
    <div class="field"><label>רוחב הרקמה (ס״מ)</label><input type="number" id="widthCm" min="1" max="35" step="0.5" value="${widthCm}" /><div class="hint" id="hInfo">גובה: ${(widthCm * aspect).toFixed(1)} ס״מ</div></div>
    <div class="field"><div class="label">הסרת רקע בבינה מלאכותית</div><div class="seg" id="aiSeg">
      <button data-a="none" class="${ai === 'none' ? 'on' : ''}">בלי</button>
      <button data-a="fast" class="${ai === 'fast' ? 'on' : ''}">מהיר</button>
      <button data-a="quality" class="${ai === 'quality' ? 'on' : ''}">איכותי</button>
      <button data-a="people" class="${ai === 'people' ? 'on' : ''}">אנשים</button>
    </div><div class="hint">מזהה את האובייקט הראשי ומוריד את הרקע. עובד בלי אינטרנט. "אנשים" מיועד לתמונות של אנשים. אחרי זה אפשר לתקן ידנית ב"מחק / החזר".</div></div>
    <div class="modal-actions"><button class="btn" id="go">המשך ✓</button><button class="btn ghost" id="cancel">ביטול</button></div>`;
  modal(html(), (el, close) => {
    const bind = () => {
      $$('#modeChoice button', el).forEach((b) => (b.onclick = () => { mode = b.dataset.m; if (mode === 'photo' && colors < 6) colors = 8; if (mode === 'logo' && colors > 8) colors = 5; el.innerHTML = html(); bind(); }));
      $$('#aiSeg button', el).forEach((b) => (b.onclick = () => { ai = b.dataset.a; $$('#aiSeg button', el).forEach((x) => x.classList.toggle('on', x === b)); }));
      $('#colors', el).oninput = (e) => { colors = +e.target.value; $('#colorsVal', el).textContent = colors; };
      $('#widthCm', el).oninput = (e) => { widthCm = Math.max(0.5, +e.target.value || 10); $('#hInfo', el).textContent = `גובה: ${(widthCm * aspect).toFixed(1)} ס״מ`; };
      $('#cancel', el).onclick = () => { close(); if (!P || !P.objects.length) show('home'); };
      $('#go', el).onclick = async () => {
        close();
        P = newProject(imp.name || 'עיצוב חדש');
        $('#projName').value = P.name;
        P.source = { kind: 'image', preview: imp.preview, mode, opts: { colors, widthMm: widthCm * 10, detail: 0.5, skipBg: true } };
        if (mode === 'photo' || mode === 'lines') P.settings.hoop = widthCm > 15 ? 'r210' : 'r150';
        selectTab('size');
        show('editor');
        if (ai !== 'none') await runAiBackground(ai);
        await redigitize();
      };
    };
    bind();
  });
}

async function openEmbroidery(imp) {
  busy(true, 'פותח קובץ רקמה…');
  try {
    const res = await call('importFile', { bytes: imp.bytes, ext: imp.ext, palette: DEFAULT_PALETTE });
    P = newProject(imp.name);
    $('#projName').value = P.name;
    res.objects.forEach((o) => { o.imported = true; o.scaled = 1; });
    P.objects = res.objects;
    show('editor');
    selectTab('colors');
    await runCompile();
    const b = compiledBounds(compiled);
    const best = HOOPS.find((h) => !h.custom && !h.cap && h.w - 10 >= b.w && h.h - 10 >= b.h && (h.shape === 'rect' || Math.hypot(b.w, b.h) <= h.w - 10));
    if (best) { P.settings.hoop = best.id; scheduleCompile(0); }
    toast(imp.ext === 'dst' || imp.ext === 'exp' ? 'הקובץ נפתח. בקובץ ' + imp.ext.toUpperCase() + ' אין צבעים – בחרו צבע לכל שלב בלשונית "צבעים".' : 'הקובץ נפתח ✓', 5000);
  } catch (e) { toast('לא הצלחנו לפתוח את הקובץ: ' + e.message, 4500); show('home'); }
  finally { busy(false); }
}

async function startOpen() {
  const f = await pickFile($('#fileEmb'));
  if (!f) return;
  try {
    const imp = await importFile(f);
    if (imp.kind === 'embroidery') await openEmbroidery(imp);
    else { show('editor'); imageSetupDialog(imp, false); }
  } catch (e) { toast(e.message, 4500); }
}

function startText() {
  P = newProject('טקסט');
  $('#projName').value = P.name;
  P.settings.hoop = 'r150';
  show('editor');
  selectTab('text');
  textDialog();
}

// ---------------- text ----------------
function textOpts(t) {
  return {
    font: t.font, heightMm: t.heightMm, bold: t.bold, weight: t.weight, lineSpacing: t.lineSpacing || 1.15, letterSpacing: t.letterSpacing || 0,
    shape: t.shape || (t.arc ? 'arc' : 'straight'), arc: t.arc || 0, slant: t.slant || 0,
    circleDiameter: t.circleDiameter || 80, circleRotate: t.circleRotate || 0, circleBottom: !!t.circleBottom,
    waveHeight: t.waveHeight ?? 40, waveCount: t.waveCount ?? 1, wavePhase: t.wavePhase || 0,
  };
}

// stitch direction choices (angle of the stitches, degrees; y points down)
const DIRS = [['auto', 'אוטומטי', null], ['h', '↔ שוכב', 0], ['v', '↕ עומד (מלמעלה למטה)', 90], ['d1', '⟋ אלכסון', 135], ['d2', '⟍ אלכסון', 45]];
function dirKey(angle) {
  if (angle == null) return 'auto';
  const deg = (((angle * 180) / Math.PI) % 180 + 180) % 180;
  const d = DIRS.find((x) => x[2] != null && Math.abs(x[2] - deg) < 1);
  return d ? d[0] : 'custom';
}
function dirAngle(key, customDeg) {
  if (key === 'custom') return ((+customDeg || 0) * Math.PI) / 180;
  const d = DIRS.find((x) => x[0] === key);
  return d && d[2] != null ? (d[2] * Math.PI) / 180 : null;
}

// a slider with − / + buttons for exact steps
function stepRange(id, label, min, max, step, value, unit = '') {
  return `<div class="field"><label>${label}: <b id="${id}v" data-unit="${unit}">${value}${unit}</b></label>
    <div class="steprange"><button type="button" class="stepb" data-step="${id}" data-d="-1" aria-label="פחות">−</button><input type="range" id="${id}" dir="ltr" min="${min}" max="${max}" step="${step}" value="${value}" /><button type="button" class="stepb" data-step="${id}" data-d="1" aria-label="יותר">+</button></div></div>`;
}
function bindStepRanges(el, onChange) {
  $$('.steprange input[type=range]', el).forEach((r) => {
    const unit = r.dataset.unit || '';
    const show = () => { const v = $('#' + r.id + 'v', el); if (v) v.textContent = (+r.value).toString() + (v.dataset.unit ?? unit); };
    r.addEventListener('input', () => { show(); onChange(); });
  });
  $$('.stepb', el).forEach((b) => {
    const r = $('#' + b.dataset.step, el);
    let timer = null;
    const once = () => {
      const st = +r.step || 1;
      const v = Math.max(+r.min, Math.min(+r.max, +r.value + st * +b.dataset.d));
      r.value = Math.round(v / st) * st;
      r.dispatchEvent(new Event('input'));
    };
    const stop = () => { clearTimeout(timer); clearInterval(timer); timer = null; };
    b.addEventListener('pointerdown', (e) => { e.preventDefault(); once(); stop(); timer = setTimeout(() => { timer = setInterval(once, 80); }, 400); });
    ['pointerup', 'pointerleave', 'pointercancel'].forEach((ev) => b.addEventListener(ev, stop));
  });
}

async function regenerateText(obj, t) {
  busy(true, 'יוצר אותיות…');
  try {
    const r = await call('text', { text: t.value, opts: textOpts(t) });
    if (!r.shapes.length) { toast('לא נמצאו אותיות לרקמה'); return false; }
    // centre at target position
    const tmp = [{ id: 'x', shapes: r.shapes }];
    const bb = designBounds(tmp);
    const cx = (bb.minX + bb.maxX) / 2, cy = (bb.minY + bb.maxY) / 2;
    // existing text keeps its current place (after any resize/rotate); new text uses t.pos
    let pos = t.pos || { x: 0, y: 0 };
    if (obj.shapes && obj.shapes.length) {
      const cb = designBounds([{ shapes: obj.shapes }]);
      pos = { x: (cb.minX + cb.maxX) / 2, y: (cb.minY + cb.maxY) / 2 };
    }
    const shapes = r.shapes.map((s) => ({ outer: s.outer.map((p) => ({ x: p.x - cx + pos.x, y: p.y - cy + pos.y })), holes: s.holes.map((h) => h.map((p) => ({ x: p.x - cx + pos.x, y: p.y - cy + pos.y }))), width: s.width }));
    Object.assign(obj, { type: 'text', shapes, text: { ...t, heightMm: t.heightMm }, name: 'טקסט: ' + t.value.slice(0, 14) });
    obj.stitch = { ...(obj.stitch || {}), kind: t.kind || 'auto' };
    return true;
  } catch (e) { toast('שגיאה ביצירת טקסט: ' + e.message, 4000); return false; }
  finally { busy(false); }
}

const fontFaces = new Map();
function loadFontFace(f) {
  if (!f) return Promise.resolve('Rubik, sans-serif');
  if (!fontFaces.has(f.file)) {
    const fam = 'ff-' + f.file.replace(/[^A-Za-z0-9]/g, '');
    fontFaces.set(f.file, new FontFace(fam, `url(./fonts/${f.file})`).load().then((ff) => { document.fonts.add(ff); return `'${fam}', Rubik, sans-serif`; }).catch(() => 'Rubik, sans-serif'));
  }
  return fontFaces.get(f.file);
}
function fontOptions(sel) {
  return FONTS.map((f) => `<option value="${f.id}" ${f.id === sel ? 'selected' : ''}>${esc(f.label)}</option>`).join('');
}

function drawTextPreview(cv, rings, color) {
  const dpr = window.devicePixelRatio || 1;
  const W = cv.clientWidth || 300, H = cv.clientHeight || 150;
  cv.width = W * dpr; cv.height = H * dpr;
  const ctx = cv.getContext('2d');
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  ctx.clearRect(0, 0, W, H);
  if (!rings || !rings.length) return;
  let x0 = Infinity, y0 = Infinity, x1 = -Infinity, y1 = -Infinity;
  for (const r of rings) for (const p of r) { x0 = Math.min(x0, p.x); y0 = Math.min(y0, p.y); x1 = Math.max(x1, p.x); y1 = Math.max(y1, p.y); }
  const k = Math.min((W - 20) / (x1 - x0 || 1), (H - 20) / (y1 - y0 || 1));
  const ox = (W - (x1 - x0) * k) / 2 - x0 * k, oy = (H - (y1 - y0) * k) / 2 - y0 * k;
  ctx.beginPath();
  for (const r of rings) r.forEach((p, i) => (i ? ctx.lineTo(ox + p.x * k, oy + p.y * k) : ctx.moveTo(ox + p.x * k, oy + p.y * k)));
  ctx.fillStyle = color || '#1b1b1b';
  ctx.fill('nonzero');
}

function textDialog(existing) {
  const t = existing ? { ...existing.text } : { value: '', font: 'rubik', heightMm: 20, bold: false, arc: 0, kind: 'auto', color: '#1b1b1b', pos: null };
  if (!t.shape) t.shape = t.arc ? 'arc' : 'straight';
  let dir = existing ? dirKey(existing.stitch?.angle) : 'auto';
  let customDir = existing && existing.stitch?.angle != null ? Math.round((existing.stitch.angle * 180) / Math.PI) : 0;
  let color = existing ? existing.color : t.color;
  modal(`
    <h2>${existing ? 'עריכת טקסט' : 'הוספת טקסט'}</h2>
    <div class="field"><label>הטקסט</label><textarea id="tv" placeholder="כתבו כאן… (Enter לשורה חדשה)">${esc(t.value)}</textarea><div class="hint">כל השפות: עברית, אנגלית, ערבית, רוסית, סינית, יפנית, הודית ועוד</div></div>
    <div class="field"><label>גופן: <b id="fontName"></b></label>
      <input type="hidden" id="tf" value="${esc(t.font)}" />
      <div class="seg" id="fLang" style="margin-bottom:8px"><button data-l="he">עברית</button><button data-l="en">English</button><button data-l="other">שפות נוספות</button></div>
      <div class="chips" id="fCats"></div>
      <div class="font-grid" id="fGrid"></div>
      <div class="font-sample" id="fs"></div></div>
    <div class="field"><label>עובי האותיות</label><div class="seg" id="tw">
      <button data-w="400">רגיל</button><button data-w="600">בינוני</button><button data-w="700">עבה</button><button data-w="900">עבה מאוד</button></div>
      <div class="hint" id="twHint"></div></div>
    <div class="grid2">
      <div class="field"><label>גובה האותיות (ס״מ)</label><input type="number" id="th" min="0.3" max="30" step="0.1" value="${(t.heightMm / 10).toFixed(1)}" /></div>
      <div class="field"><label>צבע</label><div class="row"><span class="swatch" style="background:${color}"><input type="color" id="tc" value="${color}" /></span><span id="tcn">${esc(colorName(color))}</span></div></div>
    </div>
    <div class="field"><div class="label">צורת הטקסט</div><div class="seg" id="tShape">
      <button data-s="straight">▬ ישר</button><button data-s="arc">◠ קשת</button><button data-s="circle">◯ עיגול</button><button data-s="wave">〰 גל</button><button data-s="vertical">⇣ אנכי</button></div></div>
    <div id="shapeOpts"></div>
    <canvas id="tPrev" class="text-preview" aria-label="תצוגה מקדימה של הטקסט"></canvas>
    <div class="hint" id="tPrevInfo"></div>
    <details class="more"><summary>עוד סידור: הטיה, רווחים</summary>
      ${stepRange('tSlant', 'הטיה (נטוי)', -25, 25, 1, t.slant || 0, '°')}
      ${stepRange('tLs', 'רווח בין האותיות', -10, 60, 1, Math.round((t.letterSpacing || 0) * 100), '%')}
      ${stepRange('tLh', 'רווח בין השורות', 80, 250, 5, Math.round((t.lineSpacing || 1.15) * 100), '%')}
    </details>
    <div class="field"><div class="label">כיוון התפר</div><div class="chips" id="tDir">${DIRS.map(([k, l]) => `<button data-d="${k}">${l}</button>`).join('')}<button data-d="custom">זווית…</button></div>
      <div id="tDirCustom" hidden>${stepRange('tDirA', 'זווית התפר', 0, 175, 5, customDir, '°')}</div>
      <div class="hint">"עומד" = התפרים יורדים מלמעלה למטה. "אוטומטי" = הסאטן מסתובב עם צורת האות.</div></div>
    <div class="field"><div class="label">סוג תפר</div><div class="seg" id="tk">
      <button data-k="auto" class="${t.kind === 'auto' ? 'on' : ''}">אוטומטי</button><button data-k="satin" class="${t.kind === 'satin' ? 'on' : ''}">סאטן</button><button data-k="fill" class="${t.kind === 'fill' ? 'on' : ''}">מילוי</button><button data-k="run" class="${t.kind === 'run' ? 'on' : ''}">קו</button>
    </div></div>
    <div class="modal-actions"><button class="btn" id="ok">${existing ? 'עדכון' : 'הוספה'} ✓</button><button class="btn ghost" id="no">ביטול</button></div>`, (el, close) => {
    const sample = () => { $('#fs', el).textContent = $('#tv', el).value.split('\n')[0] || 'לאמא היקרה'; };
    let weight = t.weight || (FONTS.find((f) => f.id === t.font)?.weight) || (t.bold ? 700 : 400);
    const curFont = () => FONTS.find((x) => x.id === $('#tf', el).value) || FONTS[0];
    const updWeight = () => {
      const f = curFont();
      $$('#tw button', el).forEach((b) => { b.classList.toggle('on', +b.dataset.w === weight); b.disabled = !f.bold && +b.dataset.w !== 400; });
      $('#twHint', el).textContent = f.bold ? 'עבה = רקמה מלאה ובולטת יותר. מומלץ לטקסט קטן.' : 'לגופן הזה יש עובי אחד בלבד.';
    };
    $$('#tw button', el).forEach((b) => (b.onclick = () => { weight = +b.dataset.w; updWeight(); }));
    const selectFont = (id) => {
      $('#tf', el).value = id;
      const f = curFont();
      if (!f.bold) weight = 400; else if (f.weight) weight = f.weight;
      $('#fontName', el).textContent = f.label;
      $$('#fGrid .font-card', el).forEach((c) => c.classList.toggle('on', c.dataset.f === id));
      loadFontFace(f).then((fam) => { $('#fs', el).style.fontFamily = fam; $('#fs', el).style.fontWeight = weight; });
      updWeight();
    };
    let lang = (curFont().cat || 'he').startsWith('en') ? 'en' : curFont().cat === 'other' ? 'other' : 'he';
    let cat = null;
    const drawGrid = () => {
      $$('#fLang button', el).forEach((b) => b.classList.toggle('on', b.dataset.l === lang));
      const cats = FONT_CATS.filter((c) => c.lang === lang);
      $('#fCats', el).innerHTML = `<button data-c="" class="${!cat ? 'on' : ''}">הכול</button>` + cats.map((c) => `<button data-c="${c.id}" class="${cat === c.id ? 'on' : ''}">${esc(c.label)}</button>`).join('');
      $$('#fCats button', el).forEach((b) => (b.onclick = () => { cat = b.dataset.c || null; drawGrid(); }));
      const list = FONTS.filter((f) => cats.some((c) => c.id === f.cat) && (!cat || f.cat === cat));
      const sampleText = lang === 'he' ? 'שלום אמא' : lang === 'en' ? 'Hello Mom' : null;
      $('#fGrid', el).innerHTML = list.map((f) => {
        const txt = sampleText || (['naskh', 'cairo', 'amiri'].includes(f.id) ? 'مرحبا' : f.id === 'jp' ? 'こんにちは' : f.id === 'kr' ? '안녕하세요' : '你好');
        return `<button class="font-card ${f.id === $('#tf', el).value ? 'on' : ''}" data-f="${f.id}"><span class="fc-sample" data-file="${f.file}" data-id="${f.id}">${esc(txt)}</span><small>${esc(f.label)}</small></button>`;
      }).join('');
      $$('#fGrid .font-card', el).forEach((c) => (c.onclick = () => selectFont(c.dataset.f)));
      // load each sample font as it scrolls into view
      const io = new IntersectionObserver((ents) => ents.forEach((e) => {
        if (!e.isIntersecting) return;
        io.unobserve(e.target);
        const f = FONTS.find((x) => x.id === e.target.dataset.id);
        loadFontFace(f).then((fam) => { e.target.style.fontFamily = fam; e.target.style.fontWeight = f.weight || 400; });
      }), { root: $('#fGrid', el) });
      $$('#fGrid .fc-sample', el).forEach((x) => io.observe(x));
    };
    $$('#fLang button', el).forEach((b) => (b.onclick = () => { lang = b.dataset.l; cat = null; drawGrid(); }));
    drawGrid();
    selectFont($('#tf', el).value);
    sample();
    $('#tv', el).oninput = sample;
    $('#tc', el).oninput = (e) => { color = e.target.value; e.target.parentElement.style.background = color; $('#tcn', el).textContent = colorName(color); };
    // ---- text shape + live preview ----
    const drawShapeOpts = () => {
      $$('#tShape button', el).forEach((b) => b.classList.toggle('on', b.dataset.s === t.shape));
      const box = $('#shapeOpts', el);
      if (t.shape === 'arc') {
        box.innerHTML = `<div class="seg" id="arcDir" style="margin-bottom:8px"><button data-a="1" class="${(t.arc || 120) >= 0 ? 'on' : ''}">◠ קשת למעלה</button><button data-a="-1" class="${(t.arc || 120) < 0 ? 'on' : ''}">◡ קשת למטה</button></div>`
          + stepRange('arcAmt', 'כמה מעוגל', 10, 300, 5, Math.abs(t.arc || 120), '°');
      } else if (t.shape === 'circle') {
        box.innerHTML = `<div class="seg" id="circSide" style="margin-bottom:8px"><button data-b="0" class="${!t.circleBottom ? 'on' : ''}">טקסט בחלק העליון</button><button data-b="1" class="${t.circleBottom ? 'on' : ''}">טקסט בחלק התחתון</button></div>`
          + stepRange('circD', 'קוטר העיגול (ס״מ)', 2, 30, 0.5, ((t.circleDiameter || 80) / 10), '')
          + stepRange('circR', 'סיבוב על העיגול', -180, 180, 5, t.circleRotate || 0, '°');
      } else if (t.shape === 'wave') {
        box.innerHTML = stepRange('wH', 'גובה הגל', 0, 150, 5, t.waveHeight ?? 40, '%')
          + stepRange('wN', 'מספר גלים', 0.5, 4, 0.5, t.waveCount ?? 1, '')
          + stepRange('wP', 'הזזת הגל', 0, 360, 15, t.wavePhase || 0, '°');
      } else if (t.shape === 'vertical') box.innerHTML = '<div class="hint">האותיות יורדות אחת מתחת לשנייה – מתאים לשרוול, לסרט או לצד של תיק.</div>';
      else box.innerHTML = '';
      $$('#arcDir button', el).forEach((b) => (b.onclick = () => { t.arc = Math.abs(t.arc || 120) * +b.dataset.a; drawShapeOpts(); refresh(); }));
      $$('#circSide button', el).forEach((b) => (b.onclick = () => { t.circleBottom = b.dataset.b === '1'; drawShapeOpts(); refresh(); }));
      bindStepRanges(box, () => { readShape(); refresh(); });
    };
    const num = (id, d) => { const x = $('#' + id, el); return x ? +x.value : d; };
    const readShape = () => {
      if (t.shape === 'arc') t.arc = Math.sign(t.arc || 1) * num('arcAmt', 120);
      if (t.shape === 'circle') { t.circleDiameter = num('circD', 8) * 10; t.circleRotate = num('circR', 0); }
      if (t.shape === 'wave') { t.waveHeight = num('wH', 40); t.waveCount = num('wN', 1); t.wavePhase = num('wP', 0); }
      t.slant = num('tSlant', 0); t.letterSpacing = num('tLs', 0) / 100; t.lineSpacing = num('tLh', 115) / 100;
    };
    $$('#tShape button', el).forEach((b) => (b.onclick = () => {
      t.shape = b.dataset.s;
      if (t.shape === 'arc' && !t.arc) t.arc = 120;
      if (t.shape === 'circle' && !t.circleDiameter) t.circleDiameter = Math.max(50, Math.round((t.heightMm || 20) * 4));
      drawShapeOpts(); refresh();
    }));
    let pvSeq = 0, pvTimer = null;
    const refresh = () => {
      clearTimeout(pvTimer);
      pvTimer = setTimeout(async () => {
        const seq = ++pvSeq;
        if (!$('#tv', el)) return; // dialog closed
        const v = $('#tv', el).value.trim() || 'לאמא היקרה';
        const opts = textOpts({ ...t, font: $('#tf', el).value, weight, bold: weight >= 700, heightMm: Math.max(1, (+$('#th', el).value || 2) * 10) });
        try {
          const r = await call('layout', { text: v, opts });
          if (seq !== pvSeq || !$('#tPrev', el)) return;
          drawTextPreview($('#tPrev', el), r.rings, color);
          $('#tPrevInfo', el).textContent = r.widthMm ? `גודל: ${(r.widthMm / 10).toFixed(1)} × ${(r.heightMm / 10).toFixed(1)} ס״מ` : '';
        } catch { /* preview only */ }
      }, 120);
    };
    bindStepRanges($('details.more', el), () => { readShape(); refresh(); });
    drawShapeOpts();
    $('#th', el).addEventListener('input', refresh);
    $('#tv', el).addEventListener('input', refresh);
    $('#tc', el).addEventListener('input', refresh);
    $('#fGrid', el).addEventListener('click', () => setTimeout(refresh, 0));
    $('#tw', el).addEventListener('click', () => setTimeout(refresh, 0));
    // ---- stitch direction ----
    const drawDir = () => { $$('#tDir button', el).forEach((b) => b.classList.toggle('on', b.dataset.d === dir)); $('#tDirCustom', el).hidden = dir !== 'custom'; };
    $$('#tDir button', el).forEach((b) => (b.onclick = () => { dir = b.dataset.d; drawDir(); }));
    bindStepRanges($('#tDirCustom', el), () => { customDir = num('tDirA', 0); });
    drawDir();
    refresh();
    $$('#tk button', el).forEach((b) => (b.onclick = () => { t.kind = b.dataset.k; $$('#tk button', el).forEach((x) => x.classList.toggle('on', x === b)); }));
    $('#no', el).onclick = () => { close(); if (!P.objects.length) show('home'); };
    $('#ok', el).onclick = async () => {
      const v = $('#tv', el).value.trim();
      if (!v) { toast('כתבו טקסט קודם'); return; }
      readShape();
      Object.assign(t, { value: v, font: $('#tf', el).value, heightMm: Math.max(1, (+$('#th', el).value || 2) * 10), weight, bold: weight >= 700 });
      if (t.shape !== 'arc') t.arc = 0;
      close();
      if (!existing) {
        // place new text under the existing design
        const b = designBounds(P.objects);
        t.pos = P.objects.length ? { x: 0, y: b.maxY + t.heightMm * 0.8 } : { x: 0, y: 0 };
      }
      const obj = existing || { id: newId(), visible: true };
      obj.color = color;
      const ok = await regenerateText(obj, t);
      if (!ok) return;
      obj.stitch = { ...(obj.stitch || {}), angle: dirAngle(dir, customDir) };
      if (!existing) P.objects.push(obj);
      if (P.name === 'טקסט' || P.name === 'עיצוב חדש') { P.name = v.split('\n')[0].slice(0, 24); $('#projName').value = P.name; }
      renderPanel();
      await runCompile();
      if (!existing && compiled) {
        const k = fitScaleForHoop();
        if (k < 1) toast('הטקסט גדול מהחישוק – לחצו "הקטן כדי שיתאים לחישוק" או בחרו חישוק גדול יותר', 4500);
      }
    };
  });
}

// ---------------- panels ----------------
function selectTab(tab) {
  currentTab = tab;
  $$('#tabs button').forEach((b) => b.classList.toggle('active', b.dataset.tab === tab));
  renderPanel();
}
$$('#tabs button').forEach((b) => (b.onclick = () => selectTab(b.dataset.tab)));

function renderPanel() {
  if (!P) return;
  $('#tabImageBtn').hidden = !P.source;
  if (currentTab === 'image' && !P.source) currentTab = 'size';
  const el = $('#panel');
  const fn = { size: panelSize, colors: panelColors, stitch: panelStitch, image: panelImage, text: panelText, view: panelView }[currentTab];
  fn(el);
}

function panelSize(el) {
  const b = compiled ? compiledBounds(compiled) : { w: 0, h: 0 };
  const h = hoop();
  el.innerHTML = `
    <div class="field"><label>חישוק</label><select id="hoop">${HOOPS.map((x) => `<option value="${x.id}" ${x.id === P.settings.hoop ? 'selected' : ''}>${esc(x.label)}</option>`).join('')}</select>
      ${h.custom ? `<div class="grid2" style="margin-top:8px"><input type="number" id="chw" value="${(P.settings.customHoop.w / 10).toFixed(1)}" step="0.5" aria-label="רוחב חישוק" /><input type="number" id="chh" value="${(P.settings.customHoop.h / 10).toFixed(1)}" step="0.5" aria-label="גובה חישוק" /></div><div class="hint">רוחב × גובה בס״מ (שטח הרקמה של החישוק)</div>` : `<div class="hint">המידות משוערות – בדקו מול החישוק שלכם. נשמר מרווח ביטחון של ${HOOP_MARGIN} מ״מ.</div>`}</div>
    <div class="grid2">
      <div class="field"><label>רוחב (ס״מ)</label><input type="number" id="dw" step="0.1" min="0.5" value="${(b.w / 10).toFixed(1)}" /></div>
      <div class="field"><label>גובה (ס״מ)</label><input type="number" id="dh" step="0.1" min="0.5" value="${(b.h / 10).toFixed(1)}" /></div>
    </div>
    <div class="row" style="margin-bottom:14px">
      <button class="btn secondary" id="fit">התאם לחישוק</button>
      <button class="btn ghost" id="rot">↻ סיבוב 90°</button>
      <button class="btn ghost" id="mir">⇋ היפוך</button>
    </div>
    <div class="field"><label>סוג הבד</label><select id="fab">${Object.entries(FABRICS).map(([k, v]) => `<option value="${k}" ${k === P.settings.fabric ? 'selected' : ''}>${esc(v.label)}</option>`).join('')}</select><div class="hint">משנה צפיפות, שכבת בסיס ופיצוי משיכה בהתאם לבד</div></div>
    ${statsHtml()}`;
  $('#hoop', el).onchange = (e) => { P.settings.hoop = e.target.value; renderPanel(); scheduleCompile(0); preview.hoop = hoop(); setTimeout(() => preview.fit(), 50); };
  if ($('#chw', el)) {
    const upd = () => { P.settings.customHoop = { w: Math.max(20, (+$('#chw', el).value || 15) * 10), h: Math.max(20, (+$('#chh', el).value || 15) * 10) }; scheduleCompile(0); setTimeout(() => preview.fit(), 50); };
    $('#chw', el).onchange = upd; $('#chh', el).onchange = upd;
  }
  $('#dw', el).onchange = (e) => { const v = +e.target.value * 10; if (v > 0 && b.w > 0) scaleDesign(v / b.w); };
  $('#dh', el).onchange = (e) => { const v = +e.target.value * 10; if (v > 0 && b.h > 0) scaleDesign(v / b.h); };
  $('#fit', el).onclick = () => scaleDesign(fitScaleForHoop());
  $('#rot', el).onclick = () => { P.objects = transformObjects(P.objects, { rotate: Math.PI / 2 }); scheduleCompile(0); };
  $('#mir', el).onclick = () => { P.objects = transformObjects(P.objects, { flipX: true }); scheduleCompile(0); };
  $('#fab', el).onchange = (e) => { P.settings.fabric = e.target.value; scheduleCompile(0); };
}

function statsHtml() {
  if (!compiled) return '';
  const st = compiledStats(compiled);
  return `<div class="stats">
    <div><b>${st.stitches.toLocaleString('he-IL')}</b><small>תפרים</small></div>
    <div><b>${compiled.blocks.length}</b><small>שלבי צבע</small></div>
    <div><b>${Math.max(1, Math.round(st.minutes))} דק׳</b><small>זמן רקמה משוער</small></div>
    <div><b>${st.trims}</b><small>חיתוכי חוט</small></div>
  </div>`;
}

function colorGroups() {
  // ordered by first appearance in P.objects (= sewing order)
  const groups = [];
  for (const o of P.objects) {
    const c = o.color.toLowerCase();
    let g = groups.find((x) => x.color === c);
    if (!g) { g = { color: c, objs: [] }; groups.push(g); }
    g.objs.push(o);
  }
  return groups;
}

function panelColors(el) {
  const groups = colorGroups();
  const nOpts = (v) => Array.from({ length: NEEDLES }, (_, i) => `<option value="${i + 1}" ${v === i + 1 ? 'selected' : ''}>מחט ${i + 1}</option>`).join('');
  el.innerHTML = `
    <div class="hint" style="margin-bottom:10px">הסדר כאן הוא סדר הרקמה. לכל צבע בחרו את המחט שעליה החוט מושחל במכונה.</div>
    ${groups.map((g, i) => {
      const hidden = g.objs.every((o) => o.visible === false);
      const blk = compiled?.blocks.find((b) => b.color.toLowerCase() === g.color);
      const n = blk ? blk.flags.reduce((s, f) => s + (f & 1 ? 0 : 1), 0) : 0;
      return `<div class="color-row ${hidden ? 'hidden-color' : ''}" data-i="${i}">
        <span class="swatch" style="background:${g.color}"><input type="color" value="${g.color}" data-col="${i}" aria-label="שינוי צבע" /></span>
        <div class="meta"><b>${i + 1}. ${esc(colorName(g.color))}</b><small>${g.color.toUpperCase()} · ${n.toLocaleString('he-IL')} תפרים</small>
          <input type="text" placeholder="שם/מספר חוט (לא חובה)" value="${esc(P.threadNames[g.color] || '')}" data-tn="${i}" style="margin-top:6px;padding:6px 8px" /></div>
        <div class="ctrls">
          <select data-needle="${i}" aria-label="מחט">${nOpts(P.needles[g.color])}</select>
          <button class="mini" data-up="${i}" aria-label="מוקדם יותר" ${i === 0 ? 'disabled' : ''}>▲</button>
          <button class="mini" data-down="${i}" aria-label="מאוחר יותר" ${i === groups.length - 1 ? 'disabled' : ''}>▼</button>
          <button class="mini" data-vis="${i}" aria-label="הסתר/הצג">${hidden ? '🙈' : '👁️'}</button>
          <button class="mini" data-hl="${i}" aria-label="הדגש">🔍</button>
          <button class="mini" data-del="${i}" aria-label="מחיקה">🗑️</button>
          ${g.objs.some((o) => o.shapes) ? `<button class="mini ${g.objs.some((o) => o.applique) ? 'on' : ''}" data-appl="${i}" title="אפליקציה – הדבקת בד">✂️ אפליקציה</button><button class="mini" data-outl="${i}" title="הוספת קו מתאר">➰ קו מתאר</button>` : ''}
        </div></div>`;
    }).join('') || '<div class="empty">אין צבעים עדיין</div>'}
    <div class="row" style="margin-top:8px">
      <button class="btn secondary" id="merge">מיזוג צבעים דומים</button>
      <button class="btn ghost" id="renum">מספור מחטים מחדש 1,2,3…</button>
    </div>`;
  $$('[data-col]', el).forEach((inp) => {
    inp.oninput = (e) => { e.target.parentElement.style.background = e.target.value; };
    inp.onchange = (e) => {
      const g = groups[+inp.dataset.col];
      const nc = e.target.value.toLowerCase();
      const needle = P.needles[g.color];
      g.objs.forEach((o) => (o.color = nc));
      if (!P.needles[nc]) P.needles[nc] = needle;
      if (P.threadNames[g.color]) P.threadNames[nc] = P.threadNames[g.color];
      scheduleCompile(0);
    };
  });
  $$('[data-tn]', el).forEach((inp) => (inp.onchange = () => { P.threadNames[groups[+inp.dataset.tn].color] = inp.value; scheduleSave(); }));
  $$('[data-needle]', el).forEach((s) => (s.onchange = () => { P.needles[groups[+s.dataset.needle].color] = +s.value; scheduleSave(); }));
  const move = (i, d) => {
    const order = groups.map((g) => g.color);
    const [c] = order.splice(i, 1);
    order.splice(i + d, 0, c);
    P.objects.sort((a, b) => order.indexOf(a.color.toLowerCase()) - order.indexOf(b.color.toLowerCase()));
    scheduleCompile(0);
  };
  $$('[data-up]', el).forEach((b) => (b.onclick = () => move(+b.dataset.up, -1)));
  $$('[data-down]', el).forEach((b) => (b.onclick = () => move(+b.dataset.down, 1)));
  $$('[data-vis]', el).forEach((b) => (b.onclick = () => { const g = groups[+b.dataset.vis]; const hide = !g.objs.every((o) => o.visible === false); g.objs.forEach((o) => (o.visible = !hide)); scheduleCompile(0); renderPanel(); }));
  $$('[data-hl]', el).forEach((b) => (b.onclick = () => {
    const g = groups[+b.dataset.hl];
    const bi = compiled?.blocks.findIndex((x) => x.color.toLowerCase() === g.color);
    preview.highlight = preview.highlight === bi ? null : bi;
    preview.request();
  }));
  $$('[data-del]', el).forEach((b) => (b.onclick = async () => {
    const g = groups[+b.dataset.del];
    if (!(await confirmBox(`למחוק את כל החלקים בצבע ${colorName(g.color)}?`, 'מחיקה'))) return;
    P.objects = P.objects.filter((o) => !g.objs.includes(o));
    preview.highlight = null;
    renderPanel(); scheduleCompile(0);
  }));
  $$('[data-appl]', el).forEach((b) => (b.onclick = () => {
    const g = groups[+b.dataset.appl];
    const on = !g.objs.some((o) => o.applique);
    g.objs.forEach((o) => { if (o.shapes) o.applique = on ? { width: 3 } : null; });
    toast(on ? 'אפליקציה: המכונה תעצור פעמיים – להנחת הבד ולגזירת העודפים. ההוראות בדף ההוראות.' : 'האפליקציה בוטלה', 5000);
    renderPanel(); scheduleCompile(0);
  }));
  $$('[data-outl]', el).forEach((b) => (b.onclick = () => addOutlineForColor(groups[+b.dataset.outl])));
  $('#merge', el).onclick = () => {
    const n = uniqueColors().length;
    if (n <= 1) return;
    P.objects = mergeSimilarColors(P.objects, Math.max(1, n - 1));
    toast('שני הצבעים הדומים ביותר מוזגו');
    scheduleCompile(0);
  };
  $('#renum', el).onclick = () => { groups.forEach((g, i) => (P.needles[g.color] = (i % NEEDLES) + 1)); renderPanel(); scheduleSave(); };
}

function panelStitch(el) {
  const groups = colorGroups();
  const kinds = [['auto', 'אוטומטי'], ['fill', 'מילוי'], ['satin', 'סאטן'], ['run', 'קו (תפר ריצה)']];
  el.innerHTML = `
    <div class="field"><label>צפיפות: <b id="dv">${Math.round((P.settings.densityFactor || 1) * 100)}%</b></label><input type="range" id="den" min="0.6" max="1.5" step="0.05" value="${P.settings.densityFactor || 1}" /><div class="hint">100% = רגיל. גבוה יותר = כיסוי מלא יותר אבל בד קשיח יותר.</div></div>
    <div class="field"><label class="switch">שכבת בסיס (מייצבת את הבד מתחת לתפרים)<input type="checkbox" id="ul" ${P.settings.underlay !== false ? 'checked' : ''} /></label></div>
    <div class="field"><label>פיצוי משיכה: <b id="pcv">${(P.settings.pullComp ?? FABRICS[P.settings.fabric || 'cotton'].pullComp).toFixed(2)} מ״מ</b></label><input type="range" id="pc" min="0" max="0.6" step="0.05" value="${P.settings.pullComp ?? FABRICS[P.settings.fabric || 'cotton'].pullComp}" /><div class="hint">החוט מושך את הבד פנימה – מרחיבים מעט את הצורות כדי שלא יהיו רווחים.</div></div>
    <h3>סוג תפר לכל צבע</h3>
    ${groups.filter((g) => g.objs.some((o) => o.shapes)).map((g) => {
      const o0 = g.objs.find((o) => o.shapes);
      const k = o0.stitch?.kind || 'auto';
      const ang = o0.stitch?.angle != null ? Math.round((o0.stitch.angle * 180) / Math.PI) : '';
      return `<div class="card"><div class="row"><span class="needle-badge" style="background:${g.color}"></span><b>${esc(colorName(g.color))}</b></div>
        <div class="grid2" style="margin-top:8px"><select data-kind="${g.color}">${kinds.map(([v, l]) => `<option value="${v}" ${v === k ? 'selected' : ''}>${l}</option>`).join('')}</select>
        <select data-dirsel="${g.color}" aria-label="כיוון התפר">${DIRS.map(([kk, l]) => `<option value="${kk}" ${dirKey(o0.stitch?.angle) === kk ? 'selected' : ''}>${l}</option>`).join('')}<option value="custom" ${dirKey(o0.stitch?.angle) === 'custom' ? 'selected' : ''}>זווית אחרת…</option></select></div>
        <div class="row" style="margin-top:6px" ${dirKey(o0.stitch?.angle) === 'custom' ? '' : 'hidden'} data-customrow="${g.color}"><span>זווית:</span><input type="number" data-angle="${g.color}" value="${ang}" min="0" max="180" step="5" aria-label="זווית תפר" style="max-width:90px" /><span>°</span></div></div>`;
    }).join('') || '<div class="empty">אין חלקים שאפשר לשנות להם תפר (קובץ מוכן או צילום בקווים).</div>'}`;
  $('#den', el).oninput = (e) => { $('#dv', el).textContent = Math.round(e.target.value * 100) + '%'; };
  $('#den', el).onchange = (e) => { P.settings.densityFactor = +e.target.value; scheduleCompile(0); };
  $('#ul', el).onchange = (e) => { P.settings.underlay = e.target.checked; scheduleCompile(0); };
  $('#pc', el).oninput = (e) => { $('#pcv', el).textContent = (+e.target.value).toFixed(2) + ' מ״מ'; };
  $('#pc', el).onchange = (e) => { P.settings.pullComp = +e.target.value; scheduleCompile(0); };
  $$('[data-kind]', el).forEach((s) => (s.onchange = () => { P.objects.forEach((o) => { if (o.color.toLowerCase() === s.dataset.kind && o.shapes) o.stitch = { ...(o.stitch || {}), kind: s.value === 'fill' ? 'fill' : s.value }; }); scheduleCompile(0); }));
  $$('[data-dirsel]', el).forEach((s) => (s.onchange = () => {
    const row = el.querySelector(`[data-customrow="${s.dataset.dirsel}"]`);
    row.hidden = s.value !== 'custom';
    const v = dirAngle(s.value, row.querySelector('input').value);
    P.objects.forEach((o) => { if (o.color.toLowerCase() === s.dataset.dirsel && o.shapes) o.stitch = { ...(o.stitch || {}), angle: v }; });
    scheduleCompile(0);
  }));
  $$('[data-angle]', el).forEach((s) => (s.onchange = () => { const v = s.value === '' ? null : (+s.value * Math.PI) / 180; P.objects.forEach((o) => { if (o.color.toLowerCase() === s.dataset.angle && o.shapes) o.stitch = { ...(o.stitch || {}), angle: v }; }); scheduleCompile(0); }));
}

function panelImage(el) {
  const s = P.source;
  const o = s.opts;
  el.innerHTML = `
    <img class="preview-img" src="${s.aiImage || s.preview}" alt="" />
    <div class="field" style="margin-top:10px"><div class="label">סוג המרה</div><div class="seg" id="md">
      <button data-m="logo" class="${s.mode === 'logo' ? 'on' : ''}">לוגו</button><button data-m="photo" class="${s.mode === 'photo' ? 'on' : ''}">צילום צבעוני</button><button data-m="lines" class="${s.mode === 'lines' ? 'on' : ''}">צילום בקווים</button></div></div>
    ${s.mode !== 'lines' ? `
    <div class="field"><label>מספר צבעים: <b id="cv">${o.colors}</b></label><input type="range" id="cc" min="1" max="12" value="${o.colors}" /></div>
    <div class="field"><label>רמת פרטים: <b id="dtv">${Math.round(o.detail * 100)}%</b></label><input type="range" id="dt" min="0" max="1" step="0.05" value="${o.detail}" /><div class="hint">פחות פרטים = רקמה נקייה ומהירה יותר</div></div>
    <div class="field"><label class="switch">לא לרקום את צבע הרקע<input type="checkbox" id="sb" ${o.skipBg !== false ? 'checked' : ''} /></label></div>` : `
    <div class="field"><label>מרווח בין הקווים: <b id="rsv">${(o.rowSpacing || 1.1).toFixed(1)} מ״מ</b></label><input type="range" id="rs" min="0.6" max="2.5" step="0.1" value="${o.rowSpacing || 1.1}" /></div>
    <div class="field"><label>זווית הקווים: <b id="anv">${o.angle || 0}°</b></label><input type="range" id="an" min="0" max="175" step="5" value="${o.angle || 0}" /></div>
    <div class="field"><label>ניגודיות: <b id="ctv">${Math.round((o.contrast || 1.2) * 100)}%</b></label><input type="range" id="ct" min="0.6" max="2.5" step="0.1" value="${o.contrast || 1.2}" /></div>
    <div class="grid2"><div class="field"><label>צבע החוט</label><span class="swatch" style="background:${o.lineColor || '#202020'}"><input type="color" id="lc" value="${o.lineColor || '#202020'}" /></span></div>
    <div class="field"><label class="switch">היפוך (לבד כהה)<input type="checkbox" id="inv" ${o.invert ? 'checked' : ''} /></label></div></div>`}
    <div class="field"><div class="label">הסרת רקע בבינה מלאכותית</div><div class="row">
      <button class="btn secondary" id="aim">🧑 אנשים</button><button class="btn secondary" id="aiq">✨ איכותי</button><button class="btn secondary" id="aif">⚡ מהיר</button>${s.aiImage ? '<button class="btn ghost" id="aio">↺ ביטול</button>' : ''}</div>
      <div class="hint">${s.aiImage ? 'הרקע הוסר ✓' : 'מזהה את האובייקט הראשי ומוריד את הרקע – בלי אינטרנט'}</div></div>
    <button class="btn secondary wide" id="cleanup" style="margin-bottom:10px">✏️ מחק / החזר חלקים בתמונה</button>
    <button class="btn wide" id="apply">🔄 המר מחדש</button>
    <div class="hint" style="margin-top:8px">המרה מחדש מחליפה את חלקי התמונה (הטקסטים נשארים). שינויי צבע ידניים יתאפסו.</div>`;
  $$('#md button', el).forEach((b) => (b.onclick = () => { s.mode = b.dataset.m; if (s.mode === 'photo' && o.colors < 6) o.colors = 8; renderPanel(); }));
  const bindRange = (id, key, lab, fmt) => { const r = $('#' + id, el); if (!r) return; r.oninput = () => { o[key] = +r.value; $('#' + lab, el).textContent = fmt(+r.value); }; };
  bindRange('cc', 'colors', 'cv', (v) => v);
  bindRange('dt', 'detail', 'dtv', (v) => Math.round(v * 100) + '%');
  bindRange('rs', 'rowSpacing', 'rsv', (v) => v.toFixed(1) + ' מ״מ');
  bindRange('an', 'angle', 'anv', (v) => v + '°');
  bindRange('ct', 'contrast', 'ctv', (v) => Math.round(v * 100) + '%');
  if ($('#sb', el)) $('#sb', el).onchange = (e) => (o.skipBg = e.target.checked);
  if ($('#lc', el)) $('#lc', el).oninput = (e) => { o.lineColor = e.target.value; e.target.parentElement.style.background = e.target.value; };
  if ($('#inv', el)) $('#inv', el).onchange = (e) => (o.invert = e.target.checked);
  $('#aiq', el).onclick = async () => { await runAiBackground('quality'); await redigitize(); };
  $('#aif', el).onclick = async () => { await runAiBackground('fast'); await redigitize(); };
  $('#aim', el).onclick = async () => { await runAiBackground('people'); await redigitize(); };
  if ($('#aio', el)) $('#aio', el).onclick = async () => { s.aiImage = null; s.aiCrop = null; o.ai = null; await redigitize(); };
  $('#apply', el).onclick = () => redigitize();
  $('#cleanup', el).onclick = () => imageCleanupDialog();
}

function panelText(el) {
  const texts = P.objects.filter((o) => o.type === 'text');
  el.innerHTML = `
    <button class="btn wide" id="addText">➕ הוספת טקסט</button>
    <div class="obj-list" style="margin-top:12px">
    ${texts.map((o) => `<div class="card"><span class="needle-badge" style="background:${o.color}"></span><div class="grow"><b>${esc(o.text.value.replace(/\n/g, ' / '))}</b><br/><small>${esc((FONTS.find((f) => f.id === o.text.font) || {}).label || '')} · ${(o.text.heightMm / 10).toFixed(1)} ס״מ</small></div>
      <div class="btns"><button class="mini" data-edit="${o.id}">✏️ עריכה</button><button class="mini" data-mvu="${o.id}" aria-label="למעלה">⬆</button><button class="mini" data-mvd="${o.id}" aria-label="למטה">⬇</button><button class="mini" data-mvl="${o.id}" aria-label="שמאלה">⬅</button><button class="mini" data-mvr="${o.id}" aria-label="ימינה">➡</button><button class="mini" data-deltext="${o.id}">🗑️</button></div></div>`).join('') || '<div class="empty">עוד אין טקסטים בעיצוב.</div>'}
    </div><div class="hint">החצים מזיזים את הטקסט ב-2 מ״מ בכל לחיצה.</div>
    ${shapesPanelHtml()}
    ${bgPanelHtml()}`;
  bindShapesPanel(el);
  bindBgPanel(el);
  $('#addText', el).onclick = () => textDialog();
  const find = (id) => P.objects.find((o) => o.id === id);
  $$('[data-edit]', el).forEach((b) => (b.onclick = () => textDialog(find(b.dataset.edit))));
  const mv = (id, dx, dy) => {
    const o = find(id);
    o.shapes = o.shapes.map((s) => ({ outer: s.outer.map((p) => ({ x: p.x + dx, y: p.y + dy })), holes: s.holes.map((h) => h.map((p) => ({ x: p.x + dx, y: p.y + dy }))), width: s.width }));
    const pos = o.text.pos || { x: 0, y: 0 };
    o.text.pos = { x: pos.x + dx, y: pos.y + dy };
    scheduleCompile(0);
  };
  $$('[data-mvu]', el).forEach((b) => (b.onclick = () => mv(b.dataset.mvu, 0, -2)));
  $$('[data-mvd]', el).forEach((b) => (b.onclick = () => mv(b.dataset.mvd, 0, 2)));
  $$('[data-mvl]', el).forEach((b) => (b.onclick = () => mv(b.dataset.mvl, -2, 0)));
  $$('[data-mvr]', el).forEach((b) => (b.onclick = () => mv(b.dataset.mvr, 2, 0)));
  $$('[data-deltext]', el).forEach((b) => (b.onclick = async () => { if (!(await confirmBox('למחוק את הטקסט?', 'מחיקה'))) return; P.objects = P.objects.filter((o) => o.id !== b.dataset.deltext); renderPanel(); scheduleCompile(0); }));
}

// ---------- stitched background / frame behind the design ----------
const BG_SHAPES = [['none', 'בלי'], ['rect', 'מלבן'], ['rounded', 'מעוגל'], ['pill', 'קפסולה'], ['ellipse', 'אליפסה'], ['circle', 'עיגול']];
function bgSpec() { return P.background || { shape: 'none', color: '#e8d9b5', margin: 6, border: true, borderColor: '#1f4e79', borderWidth: 3.5 }; }
function bgPanelHtml() {
  const b = bgSpec();
  return `<div class="card" style="margin-top:14px"><b>רקע ומסגרת רקומים מאחורי העיצוב</b>
    <div class="hint">כמו תיק טלית: רקע מלא, האותיות רקומות עליו, ומסגרת סאטן מסביב.</div>
    <div class="seg" id="bgShape" style="margin-top:8px;flex-wrap:wrap">${BG_SHAPES.map(([k, l]) => `<button data-bs="${k}" class="${b.shape === k ? 'on' : ''}">${l}</button>`).join('')}</div>
    <div class="grid2" style="margin-top:10px">
      <div class="field"><label>צבע הרקע</label><span class="swatch" style="background:${b.color}"><input type="color" id="bgCol" value="${b.color}" /></span></div>
      <div class="field"><label>מרווח מהטקסט (מ״מ)</label><input type="number" id="bgMargin" min="1" max="40" step="1" value="${b.margin}" /></div>
    </div>
    <div class="field"><label class="switch">מסגרת סאטן מסביב<input type="checkbox" id="bgBorder" ${b.border ? 'checked' : ''} /></label></div>
    <div class="grid2">
      <div class="field"><label>צבע המסגרת</label><span class="swatch" style="background:${b.borderColor}"><input type="color" id="bgBCol" value="${b.borderColor}" /></span></div>
      <div class="field"><label>עובי המסגרת (מ״מ)</label><input type="number" id="bgBW" min="1.5" max="8" step="0.5" value="${b.borderWidth}" /></div>
    </div>
    <button class="btn wide" id="bgApply">✓ החל רקע ומסגרת</button></div>`;
}
function bindBgPanel(el) {
  const b = { ...bgSpec() };
  $$('#bgShape button', el).forEach((x) => (x.onclick = () => { b.shape = x.dataset.bs; $$('#bgShape button', el).forEach((y) => y.classList.toggle('on', y === x)); }));
  $('#bgCol', el).oninput = (e) => { b.color = e.target.value; e.target.parentElement.style.background = b.color; };
  $('#bgBCol', el).oninput = (e) => { b.borderColor = e.target.value; e.target.parentElement.style.background = b.borderColor; };
  $('#bgApply', el).onclick = async () => {
    b.margin = Math.max(1, +$('#bgMargin', el).value || 6);
    b.border = $('#bgBorder', el).checked;
    b.borderWidth = Math.max(1.5, +$('#bgBW', el).value || 3.5);
    pushUndo();
    P.background = b;
    await applyBackground();
    toast(b.shape === 'none' ? 'הרקע הוסר' : 'הרקע נוסף ✓');
  };
}
async function applyBackground() {
  P.objects = P.objects.filter((o) => !o.isBackground);
  const b = P.background;
  if (!b || b.shape === 'none' || !P.objects.length) { renderPanel(); scheduleCompile(0); return; }
  busy(true, 'יוצר רקע ומסגרת…');
  try {
    const bg = await call('background', { objects: P.objects, spec: b });
    P.objects = [...bg, ...P.objects];
  } catch (e) { toast('שגיאה ביצירת הרקע: ' + e.message, 4000); }
  finally { busy(false); }
  renderPanel();
  await runCompile();
}

function panelView(el) {
  const fabrics = [['#f4f1ea', 'לבן / שמנת'], ['#1c1c1e', 'שחור'], ['#1f3a68', 'כחול נייבי'], ['#8a8f94', 'אפור'], ['#b71c1c', 'אדום'], ['#2e5d3a', 'ירוק'], ['#d9c3a5', 'בז׳']];
  el.innerHTML = `
    <div class="field"><label>צבע הבד בתצוגה</label><div class="row">${fabrics.map(([c, n]) => `<button class="swatch" data-fab="${c}" style="background:${c}" title="${n}" aria-label="${n}"></button>`).join('')}</div></div>
    <div class="field"><label class="switch">מראה חוט אמיתי<input type="checkbox" id="real" ${P.view.realistic ? 'checked' : ''} /></label></div>
    <div class="field"><label class="switch">הצג קפיצות וחיתוכים (קו מקווקו אדום)<input type="checkbox" id="jumps" ${preview.showJumps ? 'checked' : ''} /></label></div>
    <button class="btn wide" id="sim">▶ סימולציית רקמה – תפר אחרי תפר</button>`;
  $$('[data-fab]', el).forEach((b) => (b.onclick = () => { P.view.fabric = b.dataset.fab; preview.fabric = b.dataset.fab; preview.request(); scheduleSave(); }));
  $('#real', el).onchange = (e) => { P.view.realistic = e.target.checked; preview.realistic = e.target.checked; preview.request(); };
  $('#jumps', el).onchange = (e) => { preview.showJumps = e.target.checked; preview.request(); };
  $('#sim', el).onclick = startSim;
}

// ---------------- simulation ----------------
let simTimer = 0;
function startSim() {
  if (!compiled) return;
  $('#simBar').hidden = false;
  const range = $('#simRange');
  range.max = preview.total;
  range.value = 0;
  preview.limit = 0;
  preview.request();
  play();
}
function play() {
  stop();
  $('#simPlay').textContent = '⏸';
  const step = Math.max(5, Math.round(preview.total / 600));
  simTimer = setInterval(() => {
    preview.limit = Math.min(preview.total, preview.limit + step);
    $('#simRange').value = preview.limit;
    preview.request();
    if (preview.limit >= preview.total) stop();
  }, 30);
}
function stop() { clearInterval(simTimer); simTimer = 0; $('#simPlay').textContent = '▶'; }
$('#simPlay').onclick = () => (simTimer ? stop() : (preview.limit >= preview.total && (preview.limit = 0), play()));
$('#simRange').oninput = (e) => { stop(); preview.limit = +e.target.value; preview.request(); };
$('#simClose').onclick = () => { stop(); $('#simBar').hidden = true; preview.limit = Infinity; preview.request(); };

// ---------------- export ----------------
function packedToBlocks() {
  return {
    blocks: compiled.blocks.map((b) => {
      const pts = [];
      for (let i = 0; i < b.flags.length; i++) pts.push({ x: b.xy[i * 2], y: b.xy[i * 2 + 1], j: !!(b.flags[i] & 1), t: !!(b.flags[i] & 2) });
      return { color: b.color, points: pts };
    }),
  };
}

function safeName(s) { return (s || 'design').replace(/[\\/:*?"<>|]+/g, '_').trim().slice(0, 60) || 'design'; }
function machineName(s) {
  const l = (s || '').replace(/[^A-Za-z0-9_-]+/g, '-').replace(/^-+|-+$/g, '').slice(0, 24);
  if (l.length >= 2) return l;
  const d = new Date();
  return `design-${String(d.getMonth() + 1).padStart(2, '0')}${String(d.getDate()).padStart(2, '0')}-${String(d.getHours()).padStart(2, '0')}${String(d.getMinutes()).padStart(2, '0')}`;
}
function latinName(s) { const l = (s || '').replace(/[^A-Za-z0-9_-]+/g, ''); return l || 'design'; }

function exportDialog() {
  if (!compiled || !compiled.blocks.length) { toast('אין עדיין רקמה לשמירה'); return; }
  const errors = warnings.filter((w) => w.level === 'error');
  let fmt = 'dst';
  modal(`
    <h2>שמירה למכונה</h2>
    ${errors.length ? `<div class="card" style="background:#fdeceb"><b>⛔ שימו לב:</b><ul>${errors.map((e) => `<li>${esc(e.text)}</li>`).join('')}</ul></div>` : '<div class="card" style="background:#e9f6ee">✅ הבדיקות עברו – אפשר לרקום.</div>'}
    <div class="field"><label>שם הקובץ</label><input type="text" id="fn" dir="ltr" value="${esc(machineName(P.name))}" /><div class="hint">מומלץ שם באותיות אנגליות ומספרים – לא כל מכונות הרקמה מציגות עברית במסך.</div></div>
    <div class="field"><div class="label">פורמט</div><div class="choice" id="fmts">
      ${Object.entries(FORMATS).map(([k, f]) => `<button data-f="${k}" class="${k === fmt ? 'on' : ''}"><b>${esc(f.label)}</b>${k === 'dst' ? '<small>מומלץ למכונת SWF ✓</small>' : ''}</button>`).join('')}
    </div></div>
    <div class="modal-actions" style="flex-wrap:wrap"><button class="btn" id="save">💾 שמירת קובץ</button>${canShare() ? '<button class="btn secondary" id="share">📤 שיתוף / שליחה</button>' : ''}</div>
    <div class="modal-actions"><button class="btn secondary" id="sheet">📄 דף הוראות (מחטים וצבעים)</button><button class="btn secondary" id="names">📋 קובץ לכל שם</button></div>
    <div class="modal-actions"><button class="btn ghost" id="close">סגירה</button></div>
    <div class="hint">להעברה למכונה: העתיקו את קובץ ה-DST לדיסק און קי (USB) והכניסו אותו למכונה. במכונה בחרו את הקובץ וכוונו את המחטים לפי דף ההוראות.</div>`, (el, close) => {
    $$('#fmts button', el).forEach((b) => (b.onclick = () => { fmt = b.dataset.f; $$('#fmts button', el).forEach((x) => x.classList.toggle('on', x === b)); }));
    const build = () => {
      const pat = toPattern(packedToBlocks(), { name: latinName(P.name) });
      pat.colors = compiled.blocks.map((b) => b.color);
      return FORMATS[fmt].write(pat);
    };
    const fname = () => safeName($('#fn', el).value) + '.' + FORMATS[fmt].ext;
    $('#save', el).onclick = async () => {
      try {
        const r = await saveFile(fname(), build());
        if (r.ok) toast(r.shared ? 'הקובץ מוכן לשיתוף' : `נשמר ✓ ${r.where}`, 4500);
      } catch (e) { toast('השמירה נכשלה: ' + e.message, 4500); }
    };
    if ($('#share', el)) $('#share', el).onclick = async () => { try { await shareFile(fname(), build()); } catch (e) { if (!/cancel/i.test(e.message)) toast('השיתוף נכשל: ' + e.message); } };
    $('#sheet', el).onclick = () => worksheetDialog();
    $('#names', el).onclick = () => namesDialog();
    $('#close', el).onclick = close;
  });
}

function worksheetDialog() {
  const h = hoop();
  const canvas = renderWorksheet(compiled, { name: P.name, needles: P.needles, hoopLabel: h.label, fabricLabel: FABRICS[P.settings.fabric]?.label, threadNames: P.threadNames });
  const url = canvas.toDataURL('image/png');
  modal(`<h2>דף הוראות</h2><img src="${url}" style="width:100%;border-radius:12px;background:#fff" alt="דף הוראות" />
    <div class="modal-actions"><button class="btn" id="ws">💾 שמירה כתמונה</button>${canShare() ? '<button class="btn secondary" id="wsh">📤 שיתוף</button>' : ''}${platform !== 'android' ? '<button class="btn secondary" id="wp">🖨️ הדפסה</button>' : ''}</div>
    <div class="modal-actions"><button class="btn ghost" id="wc">סגירה</button></div>`, (el, close) => {
    const bytes = () => { const b64 = url.split(',')[1]; const bin = atob(b64); const u = new Uint8Array(bin.length); for (let i = 0; i < bin.length; i++) u[i] = bin.charCodeAt(i); return u; };
    const name = safeName(P.name) + ' - דף הוראות.png';
    $('#ws', el).onclick = async () => { const r = await saveFile(name, bytes(), 'image/png'); if (r.ok) toast('נשמר ✓ ' + r.where, 4000); };
    if ($('#wsh', el)) $('#wsh', el).onclick = () => shareFile(name, bytes());
    if ($('#wp', el)) $('#wp', el).onclick = () => {
      const f = document.createElement('iframe');
      f.style.position = 'fixed'; f.style.width = '0'; f.style.height = '0'; f.style.border = '0';
      document.body.appendChild(f);
      f.contentDocument.write(`<html dir="rtl"><body style="margin:0"><img src="${url}" style="width:100%" onload="setTimeout(()=>{window.print();},100)"></body></html>`);
      f.contentDocument.close();
      setTimeout(() => f.remove(), 60000);
    };
    $('#wc', el).onclick = close;
  });
}

// ---------------- home ----------------
async function renderRecent() {
  const el = $('#recent');
  let items = [];
  try { items = await listProjects(); } catch { /* storage unavailable */ }
  el.innerHTML = items.length ? items.map((p) => `<div class="item" data-open="${p.id}" role="button" tabindex="0">${p.thumb ? `<img src="${p.thumb}" alt="" />` : '<img alt="" />'}<span>${esc(p.name)}</span><button class="del" data-delp="${p.id}" aria-label="מחיקה">🗑️</button></div>`).join('') : '<div class="empty">כאן יופיעו העבודות שתשמרו. הכול נשמר אוטומטית במכשיר.</div>';
  $$('[data-open]', el).forEach((b) => (b.onclick = async (e) => {
    if (e.target.closest('[data-delp]')) return;
    const p = await getProject(b.dataset.open);
    if (!p) return;
    P = p;
    P.view = P.view || { fabric: '#f4f1ea', realistic: true };
    $('#projName').value = P.name;
    compiled = null;
    show('editor');
    selectTab('size');
    await runCompile();
  }));
  $$('[data-delp]', el).forEach((b) => (b.onclick = async (e) => {
    e.stopPropagation();
    if (!(await confirmBox('למחוק את העבודה?', 'מחיקה'))) return;
    await deleteProject(b.dataset.delp);
    renderRecent();
  }));
}

$$('[data-start]').forEach((b) => (b.onclick = () => {
  const k = b.dataset.start;
  if (k === 'image') startImage(false);
  else if (k === 'photo') startImage(true);
  else if (k === 'text') startText();
  else if (k === 'open') startOpen();
}));
$('#btnBack').onclick = async () => { if (selectMode) setSelectMode(false); stop(); $('#simBar').hidden = true; preview.limit = Infinity; clearTimeout(saveTimer); if (P && compiled) { P.thumb = thumbnail(compiled, 220, '#f7f5f0').toDataURL('image/png'); P.updated = Date.now(); try { await saveProject(JSON.parse(JSON.stringify(P))); } catch { /* ignore */ } } show('home'); renderRecent(); };
$('#btnExport').onclick = exportDialog;
$('#projName').onchange = (e) => { if (P) { P.name = e.target.value.trim() || 'עיצוב'; scheduleSave(); } };
$('#zoomIn').onclick = () => preview.zoom(1.3);
$('#zoomOut').onclick = () => preview.zoom(1 / 1.3);
$('#zoomFit').onclick = () => preview.fit();
window.addEventListener('resize', () => preview.request());

// ---------------- settings / updates ----------------
$('#btnSettings').onclick = () => {
  modal(`<h2>הגדרות ועדכונים</h2>
    <div class="card"><b>לאמא היקרה</b><br/>גרסה ${esc(APP_VERSION)} · ${platform === 'android' ? 'אנדרואיד' : platform === 'windows' ? 'Windows' : 'דפדפן'}<br/><small>הכול עובד במכשיר, בלי אינטרנט. התמונות לא נשלחות לשום מקום.</small></div>
    <button class="btn wide" id="upd">🔄 בדיקת עדכונים</button>
    <div class="hint" id="updMsg" style="margin-top:8px">עדכון מתקין גרסה חדשה מעל הקיימת – העבודות השמורות נשארות.</div>
    <div class="card" style="margin-top:12px"><b>טיפים לרקמה טובה</b><ul>
      <li>טקסט: לפחות 6 מ״מ גובה, גופן פשוט ועבה.</li>
      <li>לוגו: תמונה חדה עם מעט צבעים נותנת את התוצאה הכי נקייה.</li>
      <li>צילום: נסו קודם "הסרת רקע" ואז 6–10 צבעים.</li>
      <li>תמיד כדאי לרקום ניסיון על שארית בד לפני הבגד.</li></ul></div>
    <div class="modal-actions"><button class="btn ghost" id="cl">סגירה</button></div>`, (el, close) => {
    $('#cl', el).onclick = close;
    $('#upd', el).onclick = async () => {
      const msg = $('#updMsg', el);
      msg.textContent = 'בודק…';
      try {
        const info = await checkForUpdate();
        if (!info.available) { msg.textContent = `יש לכם את הגרסה הכי חדשה (${APP_VERSION}) ✓`; return; }
        msg.innerHTML = `יש גרסה חדשה: <b>${esc(info.version)}</b>. <button class="btn" id="inst" style="margin-top:8px">התקנת העדכון</button>`;
        $('#inst', el).onclick = () => installUpdate(info);
      } catch (e) { msg.textContent = e.message; }
    };
  });
};
onNativeUpdateEvent((ev) => {
  if (ev.type === 'available') toast(`מוריד עדכון לגרסה ${ev.version}…`, 4000);
  if (ev.type === 'downloaded') {
    confirmBox(`עדכון לגרסה ${ev.version} מוכן. להפעיל מחדש ולהתקין עכשיו?`, 'כן, עדכן').then((ok) => ok && installUpdate());
  }
});

// Android: quiet update check once a day
(async () => {
  if (platform !== 'android') return;
  try {
    const last = +localStorage.getItem('lastUpdCheck') || 0;
    if (Date.now() - last < 864e5) return;
    localStorage.setItem('lastUpdCheck', String(Date.now()));
    const info = await checkForUpdate();
    if (info.available && (await confirmBox(`יש גרסה חדשה של האפליקציה (${info.version}). להוריד ולהתקין?`, 'כן, עדכן'))) installUpdate(info);
  } catch { /* offline */ }
})();

if (platform === 'android') $('#fileEmb').setAttribute('accept', '*/*');
// Android hardware back button
if (platform === 'android') {
  import('@capacitor/app').then(({ App }) => App.addListener('backButton', () => {
    if (!$('#modal').hidden) { $('#modal').hidden = true; return; }
    if ($('#editor').classList.contains('active')) { $('#btnBack').click(); return; }
    App.exitApp();
  }));
}

// ---------------- undo / redo ----------------
const undoStack = [];
const redoStack = [];
let lastState = null;
let restoring = false;
function snapshot() { return JSON.stringify({ objects: P.objects, background: P.background || null, settings: P.settings, needles: P.needles }); }
function trackUndo() {
  if (!P) return;
  const cur = snapshot();
  if (restoring) { lastState = cur; restoring = false; updateUndoButtons(); return; }
  if (lastState !== null && cur !== lastState) {
    undoStack.push(lastState);
    if (undoStack.length > 40) undoStack.shift();
    redoStack.length = 0;
  }
  lastState = cur;
  updateUndoButtons();
}
function resetUndo() { undoStack.length = 0; redoStack.length = 0; lastState = null; updateUndoButtons(); }
function pushUndo() { /* snapshots are taken automatically on every change */ }
function restoreState(str) {
  const st = JSON.parse(str);
  P.objects = st.objects; P.background = st.background; P.settings = st.settings; P.needles = st.needles;
  restoring = true;
  clearSelection();
  renderPanel();
  scheduleCompile(0);
}
function undo() { if (!undoStack.length) return; redoStack.push(snapshot()); restoreState(undoStack.pop()); }
function redo() { if (!redoStack.length) return; undoStack.push(snapshot()); restoreState(redoStack.pop()); }
function updateUndoButtons() {
  const u = $('#btnUndo'), r = $('#btnRedo');
  if (u) u.disabled = !undoStack.length;
  if (r) r.disabled = !redoStack.length;
}
$('#btnUndo').onclick = undo;
$('#btnRedo').onclick = redo;
window.addEventListener('keydown', (e) => {
  if (!(e.ctrlKey || e.metaKey) || !$('#editor').classList.contains('active')) return;
  if (e.target.matches('input, textarea')) return;
  if (e.key.toLowerCase() === 'z' && !e.shiftKey) { e.preventDefault(); undo(); }
  if (e.key.toLowerCase() === 'y' || (e.key.toLowerCase() === 'z' && e.shiftKey)) { e.preventDefault(); redo(); }
  if (e.key === 'Delete' && selected) { e.preventDefault(); deleteSelectedPart(); }
});

// ---------------- select, delete, recolour and move parts ----------------
let selectMode = false;
let selected = null; // { obj, shapeIndex }
function setSelectMode(on) {
  selectMode = on;
  $('#btnSelect').classList.toggle('on', on);
  if (!on) clearSelection();
  else {
    const bar = $('#selBar');
    bar.innerHTML = '<div class="selrow"><b class="grow">👆 לחצו על חלק בעיצוב כדי לבחור אותו – או גררו אותו ישר באצבע</b><button data-x>✕</button></div>';
    bar.hidden = false;
    $('[data-x]', bar).onclick = () => setSelectMode(false);
  }
}
$('#btnSelect').onclick = () => setSelectMode(!selectMode);

function clearSelection() { selected = null; preview.selection = null; if (selectMode) setSelectMode(true); else $('#selBar').hidden = true; preview.request(); }

function objRings(o) {
  if (o.shapes) return o.shapes.map((s) => s.outer);
  if (o.points) {
    const b = designBounds([o]);
    return [[{ x: b.minX, y: b.minY }, { x: b.maxX, y: b.minY }, { x: b.maxX, y: b.maxY }, { x: b.minX, y: b.maxY }]];
  }
  return [];
}

function hitTest(mm) {
  for (let k = P.objects.length - 1; k >= 0; k--) {
    const o = P.objects[k];
    if (o.visible === false) continue;
    if (o.shapes) {
      for (let i = 0; i < o.shapes.length; i++) if (pointInShape(mm.x, mm.y, o.shapes[i])) return { obj: o, shapeIndex: i };
    } else if (o.points) {
      const tol = 1.5;
      for (const p of o.points) if (Math.abs(p.x - mm.x) < tol && Math.abs(p.y - mm.y) < tol) return { obj: o, shapeIndex: -1 };
    }
  }
  return null;
}

function showSelection() {
  if (!selected) return clearSelection();
  const o = selected.obj;
  const rings = selected.shapeIndex >= 0 ? [o.shapes[selected.shapeIndex].outer, ...o.shapes[selected.shapeIndex].holes] : objRings(o);
  preview.selection = { rings, offset: { x: 0, y: 0 } };
  const bar = $('#selBar');
  const part = selected.shapeIndex >= 0 && o.shapes.length > 1;
  bar.innerHTML = `
    <div class="selrow"><b class="grow">${esc(o.name || 'חלק')}</b>
      ${o.type === 'text' ? '<button data-sa="edit">✏️ עריכה</button>' : ''}
      <button data-sa="close" aria-label="סגירה">✕</button></div>
    <div class="selrow">
      ${part ? '<button data-sa="delPart">🗑️ מחק חלק</button>' : ''}
      <button data-sa="delObj">🗑️ מחק ${o.type === 'text' ? 'טקסט' : part ? 'את כל הצבע' : ''}</button>
      ${selected.shapeIndex >= 0 ? `<label class="selcolor">🎨 צבע<input type="color" value="${o.color}" data-sa="color" /></label>` : ''}
      ${o.shapes ? `<label class="dirpick">תפר <select data-sa="dir">${DIRS.map(([kk, l]) => `<option value="${kk}" ${dirKey(o.stitch?.angle) === kk ? 'selected' : ''}>${l}</option>`).join('')}${dirKey(o.stitch?.angle) === 'custom' ? '<option value="custom" selected>זווית אחרת</option>' : ''}</select></label>` : ''}
    </div>
    <div class="nudge">
      <div class="pad"><span></span><button data-nd="0,-1" aria-label="למעלה">▲</button><span></span><button data-nd="-1,0" aria-label="שמאלה">◀</button><button data-sa="center" aria-label="למרכז החישוק" title="למרכז">⊙</button><button data-nd="1,0" aria-label="ימינה">▶</button><span></span><button data-nd="0,1" aria-label="למטה">▼</button><span></span></div>
      <div class="nudge-side">
        <div class="seg small" id="ndStep"><button data-st="0.5">½ מ״מ</button><button data-st="1">1 מ״מ</button><button data-st="5">5 מ״מ</button></div>
        <div class="selrow"><button data-sa="cx">⇔ מרכז לרוחב</button><button data-sa="cy">⇕ מרכז לגובה</button></div>
        <small id="selPos"></small>
      </div>
    </div>`;
  bar.hidden = false;
  $$('[data-sa]', bar).forEach((b) => {
    if (b.dataset.sa === 'color') { b.onchange = () => recolorSelectedPart(b.value); return; }
    if (b.dataset.sa === 'dir') { b.onchange = () => { o.stitch = { ...(o.stitch || {}), angle: dirAngle(b.value) }; scheduleCompile(0); }; return; }
    b.onclick = () => ({
      delPart: deleteSelectedPart, delObj: deleteSelectedObject, close: clearSelection,
      edit: () => { const ob = o; clearSelection(); textDialog(ob); },
      center: () => centerSelected(true, true), cx: () => centerSelected(true, false), cy: () => centerSelected(false, true),
    })[b.dataset.sa]();
  });
  $$('#ndStep button', bar).forEach((b) => { b.classList.toggle('on', +b.dataset.st === nudgeStep); b.onclick = () => { nudgeStep = +b.dataset.st; $$('#ndStep button', bar).forEach((x) => x.classList.toggle('on', x === b)); }; });
  $$('[data-nd]', bar).forEach((b) => {
    const [ux, uy] = b.dataset.nd.split(',').map(Number);
    let timer = null, n = 0;
    const once = () => { n++; const k = n > 12 ? 3 : 1; nudgeSelected(ux * nudgeStep * k, uy * nudgeStep * k); };
    const stop = () => { clearTimeout(timer); clearInterval(timer); timer = null; n = 0; };
    b.addEventListener('pointerdown', (e) => { e.preventDefault(); stop(); once(); timer = setTimeout(() => { timer = setInterval(once, 70); }, 380); });
    ['pointerup', 'pointerleave', 'pointercancel'].forEach((ev) => b.addEventListener(ev, stop));
  });
  updateSelPos();
  preview.request();
}

let nudgeStep = 1;
function selectionOverlay() {
  if (!selected) return null;
  const o = selected.obj;
  const rings = o.shapes ? o.shapes.flatMap((s) => [s.outer, ...s.holes]) : objRings(o);
  return { rings, offset: { x: 0, y: 0 }, fill: o.color };
}
function updateSelPos() {
  const el = $('#selPos');
  if (!el || !selected) return;
  const b = designBounds([selected.obj]);
  const cx = (b.minX + b.maxX) / 2, cy = (b.minY + b.maxY) / 2;
  const f = (v) => (v >= 0 ? '+' : '−') + Math.abs(v / 10).toFixed(2);
  el.textContent = `מרחק מהמרכז: ${f(cx)} ס״מ לרוחב · ${f(cy)} ס״מ לגובה. גוררים באצבע או לוחצים על החצים (לחיצה ארוכה = תנועה רציפה).`;
}
function nudgeSelected(dx, dy) {
  if (!selected) return;
  translateObject(selected.obj, dx, dy);
  preview.selection = selectionOverlay();
  updateSelPos();
  preview.request();
  scheduleCompile(350);
}
function centerSelected(horiz, vert) {
  if (!selected) return;
  const b = designBounds([selected.obj]);
  nudgeSelected(horiz ? -(b.minX + b.maxX) / 2 : 0, vert ? -(b.minY + b.maxY) / 2 : 0);
}

function deleteSelectedPart() {
  if (!selected) return;
  const o = selected.obj;
  if (selected.shapeIndex >= 0 && o.shapes.length > 1) {
    o.shapes = o.shapes.filter((_, i) => i !== selected.shapeIndex);
    if (o.type === 'text') o.type = 'region'; // edited letters are no longer re-typeset
  } else P.objects = P.objects.filter((x) => x !== o);
  clearSelection();
  toast('נמחק ✓ (אפשר לבטל עם ↶)');
  renderPanel();
  scheduleCompile(0);
}
function deleteSelectedObject() {
  if (!selected) return;
  P.objects = P.objects.filter((x) => x !== selected.obj);
  clearSelection();
  toast('נמחק ✓ (אפשר לבטל עם ↶)');
  renderPanel();
  scheduleCompile(0);
}
function recolorSelectedPart(color) {
  if (!selected || selected.shapeIndex < 0) return;
  const o = selected.obj;
  const c = color.toLowerCase();
  if (o.shapes.length === 1) { o.color = c; }
  else {
    const [sh] = o.shapes.splice(selected.shapeIndex, 1);
    const n = { ...o, id: newId(), name: (o.name || 'חלק') + ' – צבע חדש', color: c, shapes: [sh], type: o.type === 'text' ? 'region' : o.type };
    delete n.text;
    const idx = P.objects.indexOf(o);
    P.objects.splice(idx + 1, 0, n);
    selected = { obj: n, shapeIndex: 0 };
  }
  showSelection();
  renderPanel();
  scheduleCompile(0);
}

function translateObject(o, dx, dy) {
  if (o.shapes) o.shapes = o.shapes.map((s) => ({ outer: s.outer.map((p) => ({ x: p.x + dx, y: p.y + dy })), holes: s.holes.map((h) => h.map((p) => ({ x: p.x + dx, y: p.y + dy }))), width: s.width }));
  if (o.points) o.points = o.points.map((p) => ({ ...p, x: p.x + dx, y: p.y + dy }));
  if (o.text && o.text.pos) o.text.pos = { x: o.text.pos.x + dx, y: o.text.pos.y + dy };
}

preview.onTap = (mm) => {
  if (!selectMode || !P) return;
  const h = hitTest(mm);
  if (!h) { clearSelection(); return; }
  selected = h;
  showSelection();
};
preview.onDragStart = (mm) => {
  if (!selectMode) return false;
  const h = hitTest(mm);
  if (!h) return false;
  if (!selected || h.obj !== selected.obj) { selected = h; showSelection(); }
  preview.selection = selectionOverlay();
  return true;
};
preview.onDrag = (dx, dy) => { if (preview.selection) preview.selection.offset = { x: dx, y: dy }; };
preview.onDragEnd = (dx, dy) => {
  if (!selected) return;
  if (Math.hypot(dx, dy) > 0.05) {
    // whole object moves (all letters of a text, all parts of a colour)
    translateObject(selected.obj, dx, dy);
    scheduleCompile(0);
  }
  showSelection();
};

// ---------------- image cleanup: erase / restore / magic eraser ----------------
async function imageCleanupDialog() {
  if (!P.source) return;
  const base = await loadImageEl(P.source.aiImage || P.source.preview);
  const orig = await loadImageEl(P.source.preview);
  const crop = P.source.aiCrop || { x: 0, y: 0 };
  const W = base.naturalWidth, H = base.naturalHeight;
  const work = document.createElement('canvas'); work.width = W; work.height = H;
  const wctx = work.getContext('2d', { willReadFrequently: true });
  wctx.drawImage(base, 0, 0);
  const origC = document.createElement('canvas'); origC.width = W; origC.height = H;
  origC.getContext('2d').drawImage(orig, -crop.x, -crop.y);
  const history = [];
  let tool = 'erase', size = Math.round(Math.max(W, H) / 30);
  modal(`<h2>ניקוי התמונה</h2>
    <div class="hint">מסמנים באצבע מה למחוק. "החזר" מחזיר חלקים שנמחקו. "מחק אזור" מוחק בלחיצה אחת אזור שלם באותו צבע.</div>
    <div class="seg" id="ctTool" style="margin:8px 0">
      <button data-t="erase" class="on">🧽 מחק</button><button data-t="restore">↩ החזר</button><button data-t="magic">✨ מחק אזור</button>
    </div>
    <div class="field"><label>גודל המברשת / רגישות</label><input type="range" id="ctSize" min="2" max="${Math.round(Math.max(W, H) / 6)}" value="${size}" /></div>
    <div style="position:relative;touch-action:none"><canvas id="ctCanvas" class="preview-img" style="max-height:52vh;width:100%;touch-action:none"></canvas></div>
    <div class="modal-actions"><button class="btn secondary" id="ctUndo">↶ בטל</button><button class="btn" id="ctSave">✓ שמור והמר</button><button class="btn ghost" id="ctClose">ביטול</button></div>`, (el, close) => {
    const cv = $('#ctCanvas', el);
    cv.width = W; cv.height = H;
    const vctx = cv.getContext('2d');
    const redraw = () => { vctx.clearRect(0, 0, W, H); vctx.drawImage(work, 0, 0); };
    redraw();
    $$('#ctTool button', el).forEach((b) => (b.onclick = () => { tool = b.dataset.t; $$('#ctTool button', el).forEach((x) => x.classList.toggle('on', x === b)); }));
    $('#ctSize', el).oninput = (e) => (size = +e.target.value);
    const toImg = (e) => { const r = cv.getBoundingClientRect(); const k = Math.min(r.width / W, r.height / H); const ox = (r.width - W * k) / 2, oy = (r.height - H * k) / 2; return { x: (e.clientX - r.left - ox) / k, y: (e.clientY - r.top - oy) / k }; };
    const save = () => { history.push(wctx.getImageData(0, 0, W, H)); if (history.length > 20) history.shift(); };
    const dab = (p, q) => {
      const steps = q ? Math.max(1, Math.ceil(Math.hypot(p.x - q.x, p.y - q.y) / (size / 4))) : 1;
      for (let i = 0; i <= steps; i++) {
        const x = q ? q.x + ((p.x - q.x) * i) / steps : p.x, y = q ? q.y + ((p.y - q.y) * i) / steps : p.y;
        wctx.save();
        wctx.beginPath(); wctx.arc(x, y, size / 2, 0, Math.PI * 2); wctx.clip();
        if (tool === 'erase') wctx.clearRect(x - size, y - size, size * 2, size * 2);
        else { wctx.clearRect(x - size, y - size, size * 2, size * 2); wctx.drawImage(origC, 0, 0); }
        wctx.restore();
      }
      redraw();
    };
    const magic = (p) => {
      const img = wctx.getImageData(0, 0, W, H), d = img.data;
      const sx = Math.round(p.x), sy = Math.round(p.y);
      if (sx < 0 || sy < 0 || sx >= W || sy >= H) return;
      const i0 = (sy * W + sx) * 4;
      if (d[i0 + 3] === 0) return;
      const tol = Math.min(120, 12 + size * 0.6);
      const r0 = d[i0], g0 = d[i0 + 1], b0 = d[i0 + 2];
      const seen = new Uint8Array(W * H);
      const stack = [sy * W + sx];
      seen[sy * W + sx] = 1;
      while (stack.length) {
        const q = stack.pop();
        const o = q * 4;
        d[o + 3] = 0;
        const x = q % W, y = (q - x) / W;
        for (const n of [x > 0 ? q - 1 : -1, x < W - 1 ? q + 1 : -1, y > 0 ? q - W : -1, y < H - 1 ? q + W : -1]) {
          if (n < 0 || seen[n]) continue;
          seen[n] = 1;
          const k = n * 4;
          if (d[k + 3] === 0) continue;
          if (Math.abs(d[k] - r0) + Math.abs(d[k + 1] - g0) + Math.abs(d[k + 2] - b0) <= tol) stack.push(n);
        }
      }
      wctx.putImageData(img, 0, 0);
      redraw();
    };
    let lastP = null, drawing = false;
    cv.addEventListener('pointerdown', (e) => { cv.setPointerCapture(e.pointerId); save(); const p = toImg(e); if (tool === 'magic') { magic(p); return; } drawing = true; lastP = p; dab(p); });
    cv.addEventListener('pointermove', (e) => { if (!drawing) return; const p = toImg(e); dab(p, lastP); lastP = p; });
    cv.addEventListener('pointerup', () => { drawing = false; lastP = null; });
    $('#ctUndo', el).onclick = () => { const h = history.pop(); if (h) { wctx.putImageData(h, 0, 0); redraw(); } };
    $('#ctClose', el).onclick = close;
    $('#ctSave', el).onclick = async () => {
      P.source.aiImage = work.toDataURL('image/png');
      P.source.aiCrop = crop;
      close();
      await redigitize();
    };
  });
}
function loadImageEl(url) { return new Promise((res, rej) => { const im = new Image(); im.onload = () => res(im); im.onerror = rej; im.src = url; }); }

// ---------------- ready-made shapes ----------------
function shapesPanelHtml() {
  return `<div class="card" style="margin-top:14px"><b>צורות מוכנות</b>
    <div class="hint">לבבות, כוכבים, מגן דוד, מסגרות, פרחים ועוד – לחצו כדי להוסיף.</div>
    <div class="lib-grid">${LIBRARY.map((it) => `<button class="lib-item" data-lib="${it.id}" title="${esc(it.label)}"><svg viewBox="-4 -4 58 58"><path d="${librarySvgPath(it.id)}" fill="#7a2e5c" fill-rule="nonzero"/></svg><small>${esc(it.label)}</small></button>`).join('')}</div></div>`;
}
function bindShapesPanel(el) {
  $$('[data-lib]', el).forEach((b) => (b.onclick = () => shapeDialog(b.dataset.lib)));
}
function shapeDialog(id) {
  const item = LIBRARY.find((x) => x.id === id);
  let color = '#b0306a', kind = 'auto';
  modal(`<h2>הוספת ${esc(item.label)}</h2>
    <div style="text-align:center"><svg viewBox="-4 -4 58 58" width="120" height="120"><path d="${librarySvgPath(id)}" fill="${color}" id="shPrev"/></svg></div>
    <div class="grid2">
      <div class="field"><label>רוחב (ס״מ)</label><input type="number" id="shW" min="0.5" max="35" step="0.5" value="5" /></div>
      <div class="field"><label>צבע</label><span class="swatch" style="background:${color}"><input type="color" id="shC" value="${color}" /></span></div>
    </div>
    <div class="field"><div class="label">איך לרקום</div><div class="seg" id="shK">
      <button data-k="auto" class="on">מלא</button><button data-k="outline">קו מתאר סאטן</button><button data-k="run">קו דק</button></div></div>
    <div class="modal-actions"><button class="btn" id="shOk">הוספה ✓</button><button class="btn ghost" id="shNo">ביטול</button></div>`, (el, close) => {
    $('#shC', el).oninput = (e) => { color = e.target.value; e.target.parentElement.style.background = color; $('#shPrev', el).setAttribute('fill', color); };
    $$('#shK button', el).forEach((b) => (b.onclick = () => { kind = b.dataset.k; $$('#shK button', el).forEach((x) => x.classList.toggle('on', x === b)); }));
    $('#shNo', el).onclick = close;
    $('#shOk', el).onclick = async () => {
      const w = Math.max(5, (+$('#shW', el).value || 5) * 10);
      close();
      busy(true, 'מוסיף צורה…');
      try {
        let shapes = await call('libShape', { id, widthMm: w });
        if (kind === 'outline') shapes = await call('outline', { shapes, widthMm: Math.max(1.5, Math.min(4, w / 18)) });
        // place it under the existing design (or in the centre when empty)
        const b = designBounds(P.objects.filter((o) => !o.isBackground));
        const sb = designBounds([{ shapes }]);
        const dy = P.objects.length ? b.maxY + 4 - sb.minY : -(sb.minY + sb.maxY) / 2;
        const dx = -(sb.minX + sb.maxX) / 2 + (P.objects.length ? (b.minX + b.maxX) / 2 : 0);
        const obj = { id: newId(), type: 'region', name: item.label, color, shapes, stitch: { kind: kind === 'outline' ? 'satin' : kind }, visible: true };
        translateObject(obj, dx, dy);
        P.objects.push(obj);
        if (P.background) await applyBackground(); else { renderPanel(); await runCompile(); }
        if (compiled && fitScaleForHoop() < 1) toast('העיצוב גדול מהחישוק – לחצו "הקטן כדי שיתאים לחישוק" או גררו את הצורה', 4500);
      } catch (e) { toast('שגיאה: ' + e.message, 4000); }
      finally { busy(false); }
    };
  });
}

// ---------------- appliqué and outline per colour ----------------
async function addOutlineForColor(g) {
  const shapes = g.objs.filter((o) => o.shapes).flatMap((o) => o.shapes);
  if (!shapes.length) { toast('אין כאן צורות שאפשר להוסיף להן קו מתאר'); return; }
  busy(true, 'יוצר קו מתאר…');
  try {
    const band = await call('outline', { shapes, widthMm: 2.2 });
    const lastIdx = Math.max(...g.objs.map((o) => P.objects.indexOf(o)));
    P.objects.splice(lastIdx + 1, 0, { id: newId(), type: 'region', name: 'קו מתאר', color: '#1b1b1b', shapes: band, stitch: { kind: 'satin' }, visible: true });
    renderPanel();
    await runCompile();
    toast('נוסף קו מתאר שחור – אפשר לשנות לו צבע ברשימה');
  } catch (e) { toast('שגיאה: ' + e.message, 4000); }
  finally { busy(false); }
}

// ---------------- many names → many files ----------------
function namesDialog() {
  const texts = P.objects.filter((o) => o.type === 'text');
  if (!texts.length) { toast('הוסיפו קודם טקסט לעיצוב (למשל שם), ואז אפשר ליצור קובץ לכל שם ברשימה', 5000); return; }
  modal(`<h2>קובץ לכל שם</h2>
    <div class="hint">מעולה לחולצות של קבוצה, מגבות לילדים או מתנות: אותו עיצוב, ושם אחר בכל קובץ.</div>
    ${texts.length > 1 ? `<div class="field"><label>איזה טקסט להחליף?</label><select id="nmT">${texts.map((t) => `<option value="${t.id}">${esc(t.text.value)}</option>`).join('')}</select></div>` : ''}
    <div class="field"><label>שמות (שם בכל שורה)</label><textarea id="nmL" placeholder="דוד&#10;שרה&#10;Noa"></textarea></div>
    <div class="field"><div class="label">פורמט</div><div class="seg" id="nmF">${Object.entries(FORMATS).map(([k], i) => `<button data-f="${k}" class="${i === 0 ? 'on' : ''}">${k.toUpperCase()}</button>`).join('')}</div></div>
    <div class="modal-actions"><button class="btn" id="nmGo">💾 צור ושמור</button><button class="btn ghost" id="nmNo">ביטול</button></div>
    <div class="hint" id="nmMsg"></div>`, (el, close) => {
    let fmt = 'dst';
    $$('#nmF button', el).forEach((b) => (b.onclick = () => { fmt = b.dataset.f; $$('#nmF button', el).forEach((x) => x.classList.toggle('on', x === b)); }));
    $('#nmNo', el).onclick = close;
    $('#nmGo', el).onclick = async () => {
      const names = $('#nmL', el).value.split('\n').map((x) => x.trim()).filter(Boolean);
      if (!names.length) { toast('כתבו לפחות שם אחד'); return; }
      const target = $('#nmT', el) ? texts.find((t) => t.id === $('#nmT', el).value) : texts[0];
      const files = [];
      try {
        for (let i = 0; i < names.length; i++) {
          $('#nmMsg', el).textContent = `יוצר ${i + 1} מתוך ${names.length}: ${names[i]}…`;
          const t = { ...target.text, value: names[i] };
          const r = await call('text', { text: t.value, opts: textOpts(t) });
          const cb = designBounds([target]);
          const nb = designBounds([{ shapes: r.shapes }]);
          const dx = (cb.minX + cb.maxX) / 2 - (nb.minX + nb.maxX) / 2, dy = (cb.minY + cb.maxY) / 2 - (nb.minY + nb.maxY) / 2;
          const replaced = { ...target, shapes: r.shapes };
          translateObject(replaced, dx, dy);
          let objs = P.objects.map((o) => (o === target ? replaced : o));
          if (P.background && P.background.shape !== 'none') {
            const bg = await call('background', { objects: objs.filter((o) => !o.isBackground), spec: P.background });
            objs = [...bg, ...objs.filter((o) => !o.isBackground)];
          }
          const comp = await call('compile', { objects: objs.filter((o) => o.visible !== false), settings: P.settings });
          const blocks = { blocks: comp.blocks.map((b) => { const pts = []; for (let k = 0; k < b.flags.length; k++) pts.push({ x: b.xy[k * 2], y: b.xy[k * 2 + 1], j: !!(b.flags[k] & 1), t: !!(b.flags[k] & 2) }); return { color: b.color, points: pts }; }) };
          const pat = toPattern(blocks, { name: latinName(names[i]) });
          pat.colors = comp.blocks.map((b) => b.color);
          files.push({ name: `${String(i + 1).padStart(2, '0')}-${machineName(names[i]) === machineName('') ? 'name' + (i + 1) : machineName(names[i])}.${FORMATS[fmt].ext}`, bytes: FORMATS[fmt].write(pat) });
        }
        const r = await saveMany(files);
        $('#nmMsg', el).textContent = r.ok ? `נשמרו ${files.length} קבצים ✓ ${r.where || ''}` : 'השמירה בוטלה';
      } catch (e) { $('#nmMsg', el).textContent = 'שגיאה: ' + e.message; }
    };
  });
}

renderRecent();
// expose for automated tests
window.__lema = { get preview() { return preview; }, get project() { return P; }, get compiled() { return compiled; }, get warnings() { return warnings; }, selectFirst() { if (!selectMode) setSelectMode(true); selected = { obj: P.objects[0], shapeIndex: 0 }; showSelection(); } };
