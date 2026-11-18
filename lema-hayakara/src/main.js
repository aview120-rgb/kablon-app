import './styles.css';
import { call, onProgress } from './app/engine.js';
import { Preview, thumbnail } from './app/render.js';
import { analyze, compiledBounds, compiledStats, NEEDLES } from './app/warnings.js';
import { HOOPS, hoopById, HOOP_MARGIN } from './app/hoops.js';
import { colorName } from './app/colornames.js';
import { renderWorksheet } from './app/worksheet.js';
import { importFile, dataUrlToImage } from './app/importer.js';
import { saveFile, shareFile, canShare, checkForUpdate, installUpdate, onNativeUpdateEvent, APP_VERSION, platform } from './app/platform.js';
import { saveProject, getProject, listProjects, deleteProject } from './app/store.js';
import { transformObjects, mergeSimilarColors, designBounds, FABRICS, toPattern, newId } from './engine/design.js';
import { FORMATS } from './engine/formats.js';
import { FONTS } from './engine/text.js';

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
    preview.setData(compiled, hoop());
    warnings = analyze({ compiled, objects: P.objects, settings: P.settings, hoop: hoop(), uniqueColors: uniqueColors().length });
    renderWarnings();
    renderInfo();
    if (['colors', 'size'].includes(currentTab)) renderPanel();
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
  else if (fix.startsWith('textMin:')) { const o = P.objects.find((x) => x.id === fix.slice(8)); if (o) { await regenerateText(o, { ...o.text, heightMm: 6 }); } }
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
  busy(true, 'הבינה המלאכותית מסירה את הרקע… (בטלפון זה יכול לקחת עד דקה)');
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
    </div><div class="hint">מזהה את האובייקט הראשי ומוריד את הרקע. עובד בלי אינטרנט.</div></div>
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
    toast('הקובץ נפתח. בקובץ DST אין צבעים – בחרו צבע לכל שלב בלשונית "צבעים".', 5000);
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
async function regenerateText(obj, t) {
  busy(true, 'יוצר אותיות…');
  try {
    const r = await call('text', { text: t.value, opts: { font: t.font, heightMm: t.heightMm, bold: t.bold, arc: t.arc, lineSpacing: t.lineSpacing || 1.15, letterSpacing: t.letterSpacing || 0 } });
    if (!r.shapes.length) { toast('לא נמצאו אותיות לרקמה'); return false; }
    // centre at target position
    const tmp = [{ id: 'x', shapes: r.shapes }];
    const bb = designBounds(tmp);
    const cx = (bb.minX + bb.maxX) / 2, cy = (bb.minY + bb.maxY) / 2;
    const pos = t.pos || { x: 0, y: 0 };
    const shapes = r.shapes.map((s) => ({ outer: s.outer.map((p) => ({ x: p.x - cx + pos.x, y: p.y - cy + pos.y })), holes: s.holes.map((h) => h.map((p) => ({ x: p.x - cx + pos.x, y: p.y - cy + pos.y }))), width: s.width }));
    Object.assign(obj, { type: 'text', shapes, text: { ...t, heightMm: t.heightMm }, name: 'טקסט: ' + t.value.slice(0, 14) });
    obj.stitch = { ...(obj.stitch || {}), kind: t.kind || 'auto' };
    return true;
  } catch (e) { toast('שגיאה ביצירת טקסט: ' + e.message, 4000); return false; }
  finally { busy(false); }
}

function fontOptions(sel) {
  return FONTS.map((f) => `<option value="${f.id}" ${f.id === sel ? 'selected' : ''}>${esc(f.label)}</option>`).join('');
}

function textDialog(existing) {
  const t = existing ? { ...existing.text } : { value: '', font: 'rubik', heightMm: 20, bold: false, arc: 0, kind: 'auto', color: '#1b1b1b', pos: null };
  let color = existing ? existing.color : t.color;
  modal(`
    <h2>${existing ? 'עריכת טקסט' : 'הוספת טקסט'}</h2>
    <div class="field"><label>הטקסט</label><textarea id="tv" placeholder="כתבו כאן… (Enter לשורה חדשה)">${esc(t.value)}</textarea><div class="hint">כל השפות: עברית, אנגלית, ערבית, רוסית, סינית, יפנית, הודית ועוד</div></div>
    <div class="field"><label>גופן</label><select id="tf">${fontOptions(t.font)}</select><div class="font-sample" id="fs"></div></div>
    <div class="grid2">
      <div class="field"><label>גובה האותיות (ס״מ)</label><input type="number" id="th" min="0.3" max="30" step="0.1" value="${(t.heightMm / 10).toFixed(1)}" /></div>
      <div class="field"><label>צבע</label><div class="row"><span class="swatch" style="background:${color}"><input type="color" id="tc" value="${color}" /></span><span id="tcn">${esc(colorName(color))}</span></div></div>
    </div>
    <div class="field"><label class="switch">אותיות עבות (בולד)<input type="checkbox" id="tb" ${t.bold ? 'checked' : ''} /></label></div>
    <div class="field"><label>קשת: <b id="tav">${t.arc}°</b></label><input type="range" id="ta" dir="ltr" min="-270" max="270" step="5" value="${t.arc}" /><div class="hint">0 = ישר · ימינה = קשת למעלה · שמאלה = קשת למטה</div></div>
    <div class="field"><div class="label">סוג תפר</div><div class="seg" id="tk">
      <button data-k="auto" class="${t.kind === 'auto' ? 'on' : ''}">אוטומטי</button><button data-k="satin" class="${t.kind === 'satin' ? 'on' : ''}">סאטן</button><button data-k="fill" class="${t.kind === 'fill' ? 'on' : ''}">מילוי</button><button data-k="run" class="${t.kind === 'run' ? 'on' : ''}">קו</button>
    </div></div>
    <div class="modal-actions"><button class="btn" id="ok">${existing ? 'עדכון' : 'הוספה'} ✓</button><button class="btn ghost" id="no">ביטול</button></div>`, (el, close) => {
    const sample = () => { $('#fs', el).textContent = $('#tv', el).value.split('\n')[0] || 'לאמא היקרה'; };
    const loadSampleFont = async () => {
      const f = FONTS.find((x) => x.id === $('#tf', el).value);
      if (!f) return;
      try {
        const ff = new FontFace('sample-' + f.id, `url(./fonts/${f.file})`);
        document.fonts.add(await ff.load());
        $('#fs', el).style.fontFamily = `'sample-${f.id}', Rubik, sans-serif`;
      } catch { /* keep default */ }
    };
    $('#tf', el).onchange = loadSampleFont;
    loadSampleFont();
    sample();
    $('#tv', el).oninput = sample;
    $('#tc', el).oninput = (e) => { color = e.target.value; e.target.parentElement.style.background = color; $('#tcn', el).textContent = colorName(color); };
    $('#ta', el).oninput = (e) => { $('#tav', el).textContent = e.target.value + '°'; };
    $$('#tk button', el).forEach((b) => (b.onclick = () => { t.kind = b.dataset.k; $$('#tk button', el).forEach((x) => x.classList.toggle('on', x === b)); }));
    $('#no', el).onclick = () => { close(); if (!P.objects.length) show('home'); };
    $('#ok', el).onclick = async () => {
      const v = $('#tv', el).value.trim();
      if (!v) { toast('כתבו טקסט קודם'); return; }
      Object.assign(t, { value: v, font: $('#tf', el).value, heightMm: Math.max(1, (+$('#th', el).value || 2) * 10), bold: $('#tb', el).checked, arc: +$('#ta', el).value });
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
        <input type="number" data-angle="${g.color}" placeholder="זווית (אוטו׳)" value="${ang}" min="0" max="180" step="5" aria-label="זווית תפר" /></div></div>`;
    }).join('') || '<div class="empty">אין חלקים שאפשר לשנות להם תפר (קובץ מוכן או צילום בקווים).</div>'}`;
  $('#den', el).oninput = (e) => { $('#dv', el).textContent = Math.round(e.target.value * 100) + '%'; };
  $('#den', el).onchange = (e) => { P.settings.densityFactor = +e.target.value; scheduleCompile(0); };
  $('#ul', el).onchange = (e) => { P.settings.underlay = e.target.checked; scheduleCompile(0); };
  $('#pc', el).oninput = (e) => { $('#pcv', el).textContent = (+e.target.value).toFixed(2) + ' מ״מ'; };
  $('#pc', el).onchange = (e) => { P.settings.pullComp = +e.target.value; scheduleCompile(0); };
  $$('[data-kind]', el).forEach((s) => (s.onchange = () => { P.objects.forEach((o) => { if (o.color.toLowerCase() === s.dataset.kind && o.shapes) o.stitch = { ...(o.stitch || {}), kind: s.value === 'fill' ? 'fill' : s.value }; }); scheduleCompile(0); }));
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
      <button class="btn secondary" id="aiq">✨ איכותי</button><button class="btn secondary" id="aif">⚡ מהיר</button>${s.aiImage ? '<button class="btn ghost" id="aio">↺ ביטול</button>' : ''}</div>
      <div class="hint">${s.aiImage ? 'הרקע הוסר ✓' : 'מזהה את האובייקט הראשי ומוריד את הרקע – בלי אינטרנט'}</div></div>
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
  if ($('#aio', el)) $('#aio', el).onclick = async () => { s.aiImage = null; o.ai = null; await redigitize(); };
  $('#apply', el).onclick = () => redigitize();
}

function panelText(el) {
  const texts = P.objects.filter((o) => o.type === 'text');
  el.innerHTML = `
    <button class="btn wide" id="addText">➕ הוספת טקסט</button>
    <div class="obj-list" style="margin-top:12px">
    ${texts.map((o) => `<div class="card"><span class="needle-badge" style="background:${o.color}"></span><div class="grow"><b>${esc(o.text.value.replace(/\n/g, ' / '))}</b><br/><small>${esc((FONTS.find((f) => f.id === o.text.font) || {}).label || '')} · ${(o.text.heightMm / 10).toFixed(1)} ס״מ</small></div>
      <div class="btns"><button class="mini" data-edit="${o.id}">✏️ עריכה</button><button class="mini" data-mvu="${o.id}" aria-label="למעלה">⬆</button><button class="mini" data-mvd="${o.id}" aria-label="למטה">⬇</button><button class="mini" data-mvl="${o.id}" aria-label="שמאלה">⬅</button><button class="mini" data-mvr="${o.id}" aria-label="ימינה">➡</button><button class="mini" data-deltext="${o.id}">🗑️</button></div></div>`).join('') || '<div class="empty">עוד אין טקסטים בעיצוב.</div>'}
    </div><div class="hint">החצים מזיזים את הטקסט ב-2 מ״מ בכל לחיצה.</div>`;
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
function latinName(s) { const l = (s || '').replace(/[^A-Za-z0-9_-]+/g, ''); return l || 'design'; }

function exportDialog() {
  if (!compiled || !compiled.blocks.length) { toast('אין עדיין רקמה לשמירה'); return; }
  const errors = warnings.filter((w) => w.level === 'error');
  let fmt = 'dst';
  modal(`
    <h2>שמירה למכונה</h2>
    ${errors.length ? `<div class="card" style="background:#fdeceb"><b>⛔ שימו לב:</b><ul>${errors.map((e) => `<li>${esc(e.text)}</li>`).join('')}</ul></div>` : '<div class="card" style="background:#e9f6ee">✅ הבדיקות עברו – אפשר לרקום.</div>'}
    <div class="field"><label>שם הקובץ</label><input type="text" id="fn" value="${esc(safeName(P.name))}" /></div>
    <div class="field"><div class="label">פורמט</div><div class="choice" id="fmts">
      ${Object.entries(FORMATS).map(([k, f]) => `<button data-f="${k}" class="${k === fmt ? 'on' : ''}"><b>${esc(f.label)}</b>${k === 'dst' ? '<small>מומלץ למכונת SWF ✓</small>' : ''}</button>`).join('')}
    </div></div>
    <div class="modal-actions" style="flex-wrap:wrap"><button class="btn" id="save">💾 שמירת קובץ</button>${canShare() ? '<button class="btn secondary" id="share">📤 שיתוף / שליחה</button>' : ''}</div>
    <div class="modal-actions"><button class="btn secondary" id="sheet">📄 דף הוראות (מחטים וצבעים)</button><button class="btn ghost" id="close">סגירה</button></div>
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
$('#btnBack').onclick = async () => { stop(); $('#simBar').hidden = true; preview.limit = Infinity; clearTimeout(saveTimer); if (P && compiled) { P.thumb = thumbnail(compiled, 220, '#f7f5f0').toDataURL('image/png'); P.updated = Date.now(); try { await saveProject(JSON.parse(JSON.stringify(P))); } catch { /* ignore */ } } show('home'); renderRecent(); };
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

renderRecent();
// expose for automated tests
window.__lema = { get project() { return P; }, get compiled() { return compiled; }, get warnings() { return warnings; } };
