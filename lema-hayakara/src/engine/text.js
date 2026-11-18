// Text → embroidery outlines for every script, using HarfBuzz shaping,
// Unicode bidi (Hebrew/Arabic mixed with Latin) and per-character font fallback.
import * as hb from 'harfbuzzjs';
import bidiFactory from 'bidi-js';
import { parseSvgPath } from './geom.js';
import { rasterizeRings, shapesFromMask } from './vector.js';

const bidi = bidiFactory();

// User-selectable fonts. `file` lives in /fonts.
export const FONTS = [
  { id: 'rubik', label: 'רוביק – עגול ונקי', file: 'Rubik-VF.ttf', bold: true },
  { id: 'heebo', label: 'היבו – מודרני', file: 'Heebo-VF.ttf', bold: true },
  { id: 'assistant', label: 'אסיסטנט – דק ואלגנטי', file: 'Assistant-VF.ttf', bold: true },
  { id: 'secular', label: 'סקולר – עבה', file: 'SecularOne-Regular.ttf' },
  { id: 'suez', label: 'סואץ – סריף עבה', file: 'SuezOne-Regular.ttf' },
  { id: 'frank', label: 'פרנק רוהל – ספר / תורני', file: 'FrankRuhlLibre-VF.ttf', bold: true },
  { id: 'david', label: 'דוד – קלאסי', file: 'DavidLibre-Regular.ttf' },
  { id: 'miriam', label: 'מרים', file: 'MiriamLibre-VF.ttf', bold: true },
  { id: 'alef', label: 'אלף', file: 'Alef-Regular.ttf' },
  { id: 'varela', label: 'ורלה – עגול', file: 'VarelaRound-Regular.ttf' },
  { id: 'bellefair', label: 'בלפייר – חגיגי', file: 'Bellefair-Regular.ttf' },
  { id: 'amatic', label: 'אמטיק – כתב יד', file: 'AmaticSC-Regular.ttf' },
  { id: 'karantina', label: 'קרנטינה – צר וגבוה', file: 'Karantina-Regular.ttf' },
  { id: 'solitreo', label: 'סוליטראו – כתב רהוט', file: 'Solitreo-Regular.ttf' },
  { id: 'notohebrew', label: 'נוטו עברית', file: 'NotoSansHebrew-VF.ttf', bold: true },
  { id: 'notosans', label: 'Noto Sans – לועזית, יוונית, רוסית', file: 'NotoSans-VF.ttf', bold: true },
  { id: 'notoserif', label: 'Noto Serif – סריף לועזי', file: 'NotoSerif-VF.ttf', bold: true },
  { id: 'pacifico', label: 'Pacifico – כתב יד', file: 'Pacifico-Regular.ttf' },
  { id: 'lobster', label: 'Lobster – כתב מסולסל', file: 'Lobster-Regular.ttf' },
  { id: 'greatvibes', label: 'Great Vibes – כתב חתונה', file: 'GreatVibes-Regular.ttf' },
  { id: 'dancing', label: 'Dancing Script – כתב יד', file: 'DancingScript-VF.ttf', bold: true },
  { id: 'caveat', label: 'Caveat – כתב יד פשוט', file: 'Caveat-VF.ttf', bold: true },
  { id: 'oswald', label: 'Oswald – צר וחזק', file: 'Oswald-VF.ttf', bold: true },
  { id: 'bebas', label: 'Bebas Neue – כותרות', file: 'BebasNeue-Regular.ttf' },
  { id: 'anton', label: 'Anton – כותרות עבות', file: 'Anton-Regular.ttf' },
  { id: 'playfair', label: 'Playfair – אלגנטי', file: 'PlayfairDisplay-VF.ttf', bold: true },
  { id: 'naskh', label: 'נסח – ערבית', file: 'NotoNaskhArabic-VF.ttf', bold: true },
  { id: 'cairo', label: 'קהיר – ערבית מודרנית', file: 'Cairo-VF.ttf', bold: true },
  { id: 'amiri', label: 'אמירי – ערבית קלאסית', file: 'Amiri-Regular.ttf' },
  { id: 'sc', label: 'סינית מפושטת', file: 'NotoSansSC-VF.ttf', bold: true },
  { id: 'tc', label: 'סינית מסורתית', file: 'NotoSansTC-VF.ttf', bold: true },
  { id: 'jp', label: 'יפנית', file: 'NotoSansJP-VF.ttf', bold: true },
  { id: 'kr', label: 'קוריאנית', file: 'NotoSansKR-VF.ttf', bold: true },
];

// Fallback chain – any character not in the chosen font is taken from these.
const FALLBACK_FILES = [
  'NotoSans-VF.ttf', 'NotoSansHebrew-VF.ttf', 'NotoNaskhArabic-VF.ttf', 'NotoSansDevanagari-VF.ttf',
  'NotoSansBengali-VF.ttf', 'NotoSansTamil-VF.ttf', 'NotoSansThai-VF.ttf', 'NotoSansArmenian-VF.ttf',
  'NotoSansGeorgian-VF.ttf', 'NotoSansEthiopic-VF.ttf', 'NotoSansGujarati-VF.ttf', 'NotoSansGurmukhi-VF.ttf',
  'NotoSansKannada-VF.ttf', 'NotoSansMalayalam-VF.ttf', 'NotoSansTelugu-VF.ttf', 'NotoSansSinhala-VF.ttf',
  'NotoSansKhmer-VF.ttf', 'NotoSansLao-VF.ttf', 'NotoSansMyanmar-VF.ttf', 'NotoSansJP-VF.ttf',
  'NotoSansSC-VF.ttf', 'NotoSansTC-VF.ttf', 'NotoSansKR-VF.ttf', 'NotoSansSymbols2-Regular.ttf', 'NotoSansSymbols-VF.ttf',
];

let loader = null;
const cache = new Map();
export function setFontLoader(fn) { loader = fn; }

async function getFont(file) {
  if (cache.has(file)) return cache.get(file);
  const p = (async () => {
    const bytes = await loader(file);
    const blob = new hb.Blob(bytes);
    const face = new hb.Face(blob, 0);
    const font = new hb.Font(face);
    return { file, face, font, upem: face.upem, axes: face.getAxisInfos ? face.getAxisInfos() : {} };
  })();
  cache.set(file, p);
  return p;
}

function codepoints(str) { return Array.from(str); }

// Choose a font per character (by grapheme-ish code point) with fallback.
async function pickFonts(chars, primary) {
  const res = [];
  const loaded = [await getFont(primary)];
  for (const ch of chars) {
    const cp = ch.codePointAt(0);
    if (cp === 32 || cp === 10 || cp < 32 || (cp >= 0x200c && cp <= 0x200f) || cp === 0xfe0f) { res.push(null); continue; }
    let found = null;
    for (const f of loaded) if (f.font.nominalGlyph(cp)) { found = f; break; }
    if (!found) {
      for (const file of FALLBACK_FILES) {
        if (loaded.some((l) => l.file === file)) continue;
        const f = await getFont(file);
        loaded.push(f);
        if (f.font.nominalGlyph(cp)) { found = f; break; }
      }
    }
    res.push(found || 'missing');
  }
  // combining marks / spaces inherit the previous font so shaping stays intact
  for (let i = 0; i < res.length; i++) if (res[i] === null) res[i] = (res[i - 1] && res[i - 1] !== 'missing' ? res[i - 1] : null) || (res[i + 1] && res[i + 1] !== 'missing' ? res[i + 1] : null) || loaded[0];
  return res;
}

/**
 * Lay out text and return glyph rings in mm (y down).
 * opts: { font: id, heightMm, bold, letterSpacing (em), lineSpacing, arc (degrees, + = up), align }
 */
export async function layoutText(text, opts = {}) {
  const o = { font: 'rubik', heightMm: 20, bold: false, letterSpacing: 0, lineSpacing: 1.15, arc: 0, align: 'center', ...opts };
  const fdef = FONTS.find((f) => f.id === o.font) || FONTS[0];
  const lines = text.replace(/\r/g, '').split('\n');
  const outLines = [];
  for (const line of lines) {
    const chars = codepoints(line);
    if (!chars.length) { outLines.push({ glyphs: [], width: 0 }); continue; }
    let fonts = await pickFonts(chars, fdef.file);
    // drop characters no font can draw (e.g. colour emoji) instead of printing boxes
    const keep = fonts.map((f) => f !== 'missing');
    chars.splice(0, chars.length, ...chars.filter((_, i) => keep[i]));
    fonts = fonts.filter((_, i) => keep[i]);
    if (!chars.length) { outLines.push({ glyphs: [], width: 0 }); continue; }
    const str = chars.join('');
    const lv = bidi.getEmbeddingLevels(str);
    // levels are per UTF-16 unit; map to code points
    const cpLevels = [];
    let u = 0;
    for (const ch of chars) { cpLevels.push(lv.levels[u]); u += ch.length; }
    // runs of same font + level
    const runs = [];
    for (let i = 0; i < chars.length; i++) {
      const last = runs[runs.length - 1];
      if (last && last.font === fonts[i] && last.level === cpLevels[i]) last.chars.push(chars[i]);
      else runs.push({ font: fonts[i], level: cpLevels[i], chars: [chars[i]] });
    }
    // visual reordering of runs (rule L2)
    const maxL = Math.max(...runs.map((r) => r.level));
    const minOdd = Math.min(...runs.map((r) => r.level).filter((l) => l % 2 === 1), 99);
    for (let L = maxL; L >= minOdd && L > 0; L--) {
      for (let i = 0; i < runs.length;) {
        if (runs[i].level >= L) {
          let j = i; while (j < runs.length && runs[j].level >= L) j++;
          const seg = runs.slice(i, j).reverse();
          runs.splice(i, j - i, ...seg);
          i = j;
        } else i++;
      }
    }
    const glyphs = [];
    let penX = 0;
    for (const run of runs) {
      const f = run.font;
      if (f.axes && f.axes.wght) {
        const ax = f.axes.wght;
        const want = o.bold ? 700 : 400;
        const v = Math.max(ax.minValue ?? want, Math.min(ax.maxValue ?? want, want));
        f.font.setVariations([new hb.Variation('wght', v)]);
      }
      const buf = new hb.Buffer();
      buf.addText(run.chars.join(''));
      buf.guessSegmentProperties();
      buf.setDirection(run.level % 2 ? hb.Direction.RTL : hb.Direction.LTR);
      hb.shape(f.font, buf);
      const infos = buf.getGlyphInfos();
      const pos = buf.getGlyphPositions();
      const scale = 1 / f.upem; // em units
      for (let i = 0; i < infos.length; i++) {
        const gid = infos[i].codepoint;
        const path = gid ? f.font.glyphToPath(gid) : '';
        const rings = path ? parseSvgPath(path, 4).map((r) => r.map((p) => ({
          x: penX + (pos[i].xOffset + p.x) * scale,
          y: -(pos[i].yOffset + p.y) * scale,
        }))) : [];
        const adv = pos[i].xAdvance * scale;
        glyphs.push({ rings, x0: penX, adv });
        penX += adv + o.letterSpacing;
      }
    }
    outLines.push({ glyphs, width: penX - (glyphs.length ? o.letterSpacing : 0) });
  }
  // stack lines; units still em; baseline of line k at k*lineSpacing
  const maxW = Math.max(0, ...outLines.map((l) => l.width));
  const straight = [];
  const glyphList = [];
  outLines.forEach((ln, k) => {
    const dx = o.align === 'center' ? (maxW - ln.width) / 2 : o.align === 'right' ? maxW - ln.width : 0;
    for (const g of ln.glyphs) {
      const moved = g.rings.map((r) => r.map((p) => ({ x: p.x + dx, y: p.y + k * o.lineSpacing })));
      straight.push(...moved);
      glyphList.push({ rings: moved, cx: g.x0 + dx + g.adv / 2, line: k });
    }
  });
  // letter height = ink height of the tallest single line equals heightMm
  let lineH = 0;
  outLines.forEach((ln) => {
    let a = Infinity, b = -Infinity;
    for (const g of ln.glyphs) for (const r of g.rings) for (const p of r) { a = Math.min(a, p.y); b = Math.max(b, p.y); }
    if (b > a) lineH = Math.max(lineH, b - a);
  });
  if (!lineH) return { rings: [], widthMm: 0, heightMm: 0 };
  const s = o.heightMm / lineH;
  const rings = o.arc ? applyArc(glyphList, maxW, o.arc) : straight;
  let bx0 = Infinity, by0 = Infinity, bx1 = -Infinity, by1 = -Infinity;
  for (const r of rings) for (const p of r) { bx0 = Math.min(bx0, p.x); by0 = Math.min(by0, p.y); bx1 = Math.max(bx1, p.x); by1 = Math.max(by1, p.y); }
  const final = rings.map((r) => r.map((p) => ({ x: (p.x - bx0) * s, y: (p.y - by0) * s })));
  return { rings: final, widthMm: (bx1 - bx0) * s, heightMm: (by1 - by0) * s };
}

function applyArc(glyphs, width, arcDeg) {
  // arcDeg: total angle the text spans; positive bends like a smile upside-down (top arc)
  const total = (Math.abs(arcDeg) * Math.PI) / 180;
  const R = width / Math.max(total, 1e-3);
  const up = arcDeg > 0;
  const out = [];
  for (const g of glyphs) {
    const theta = (g.cx - width / 2) / R; // angle from top
    const c = Math.cos(theta), s = Math.sin(theta);
    for (const r of g.rings) {
      out.push(r.map((p) => {
        const lx = p.x - g.cx; // local x relative to glyph centre
        const ly = p.y;        // baseline at 0, y down (negative = above baseline)
        if (up) {
          // circle centre below the text
          const rad = R - ly;
          const ang = theta + lx / R;
          return { x: Math.sin(ang) * rad, y: -Math.cos(ang) * rad + R };
        }
        const rad = R + ly;
        const ang = theta + lx / R;
        return { x: Math.sin(ang) * rad, y: Math.cos(ang) * rad - R };
      }));
      void c; void s;
    }
  }
  return out;
}

/** Build embroidery shapes (with widths) for text, ready for the design. */
export async function textToShapes(text, opts = {}) {
  const lay = await layoutText(text, opts);
  if (!lay.rings.length) return { shapes: [], widthMm: 0, heightMm: 0 };
  const res = Math.max(0.03, Math.min(0.08, Math.max(lay.widthMm, lay.heightMm) / 2500));
  const { mask, w, h, ox, oy } = rasterizeRings(lay.rings, res, 1);
  const shapes = shapesFromMask(mask, w, h, res, ox, oy, { smooth: 1, minAreaMm2: 0.05 });
  return { shapes, widthMm: lay.widthMm, heightMm: lay.heightMm };
}
