// Ready-made shapes (original, drawn with simple geometry) and outline/appliqué helpers.
import { rasterizeRings, shapesFromMask } from './vector.js';
import { distanceTransform } from './raster.js';
import { pointInRing } from './geom.js';

const TAU = Math.PI * 2;
const circle = (cx, cy, r, n = 96) => Array.from({ length: n }, (_, i) => ({ x: cx + r * Math.cos((i / n) * TAU), y: cy + r * Math.sin((i / n) * TAU) }));
const ellipse = (cx, cy, rx, ry, n = 96, rot = 0) => Array.from({ length: n }, (_, i) => {
  const t = (i / n) * TAU, x = rx * Math.cos(t), y = ry * Math.sin(t);
  return { x: cx + x * Math.cos(rot) - y * Math.sin(rot), y: cy + x * Math.sin(rot) + y * Math.cos(rot) };
});
const rev = (r) => r.slice().reverse();
const roundRect = (x0, y0, x1, y1, r) => {
  const pts = [];
  const c = (cx, cy, a0) => { for (let k = 0; k <= 12; k++) { const t = a0 + (k / 12) * (Math.PI / 2); pts.push({ x: cx + r * Math.cos(t), y: cy + r * Math.sin(t) }); } };
  c(x1 - r, y0 + r, -Math.PI / 2); c(x1 - r, y1 - r, 0); c(x0 + r, y1 - r, Math.PI / 2); c(x0 + r, y0 + r, Math.PI);
  return pts;
};
const star = (cx, cy, R, r, n, rot = -Math.PI / 2) => Array.from({ length: n * 2 }, (_, i) => {
  const rad = i % 2 ? r : R, t = rot + (i / (n * 2)) * TAU;
  return { x: cx + rad * Math.cos(t), y: cy + rad * Math.sin(t) };
});
const heart = (cx, cy, s) => Array.from({ length: 120 }, (_, i) => {
  const t = (i / 120) * TAU;
  const x = 16 * Math.sin(t) ** 3, y = -(13 * Math.cos(t) - 5 * Math.cos(2 * t) - 2 * Math.cos(3 * t) - Math.cos(4 * t));
  return { x: cx + (x * s) / 17, y: cy + (y * s) / 17 };
});

// Each returns rings in a ~50mm box (nonzero fill; reversed rings make holes).
export const LIBRARY = [
  { id: 'heart', label: 'לב', rings: () => [heart(25, 26, 24)] },
  { id: 'heartframe', label: 'מסגרת לב', rings: () => [heart(25, 26, 24), rev(heart(25, 26.5, 19.5))] },
  { id: 'twohearts', label: 'שני לבבות', rings: () => [heart(18, 24, 15), heart(33, 30, 15)] },
  { id: 'star', label: 'כוכב', rings: () => [star(25, 26, 25, 10, 5)] },
  { id: 'magen', label: 'מגן דוד', rings: () => {
    const tri = (rot) => Array.from({ length: 3 }, (_, i) => ({ x: 25 + 25 * Math.cos(rot + (i / 3) * TAU), y: 25 + 25 * Math.sin(rot + (i / 3) * TAU) }));
    const triIn = (rot) => rev(Array.from({ length: 3 }, (_, i) => ({ x: 25 + 17 * Math.cos(rot + (i / 3) * TAU), y: 25 + 17 * Math.sin(rot + (i / 3) * TAU) })));
    return [tri(-Math.PI / 2), triIn(-Math.PI / 2), tri(Math.PI / 2), triIn(Math.PI / 2)];
  } },
  { id: 'circle', label: 'עיגול', rings: () => [circle(25, 25, 25)] },
  { id: 'ring', label: 'מסגרת עגולה', rings: () => [circle(25, 25, 25), rev(circle(25, 25, 21))] },
  { id: 'oval', label: 'מסגרת אליפסה', rings: () => [ellipse(25, 18, 25, 18), rev(ellipse(25, 18, 21.5, 14.5))] },
  { id: 'rect', label: 'מסגרת מלבן', rings: () => [roundRect(0, 0, 50, 34, 2), rev(roundRect(3.5, 3.5, 46.5, 30.5, 1))] },
  { id: 'rounded', label: 'מסגרת מעוגלת', rings: () => [roundRect(0, 0, 50, 34, 9), rev(roundRect(3.5, 3.5, 46.5, 30.5, 6))] },
  { id: 'double', label: 'מסגרת כפולה', rings: () => [roundRect(0, 0, 50, 34, 7), rev(roundRect(2.5, 2.5, 47.5, 31.5, 5)), roundRect(4.5, 4.5, 45.5, 29.5, 4), rev(roundRect(6.3, 6.3, 43.7, 27.7, 3))] },
  { id: 'flower', label: 'פרח', rings: () => { const r = []; for (let k = 0; k < 6; k++) { const t = (k / 6) * TAU; r.push(ellipse(25 + 13 * Math.cos(t), 25 + 13 * Math.sin(t), 11, 6.5, 64, t)); } r.push(circle(25, 25, 7)); return r; } },
  { id: 'daisy', label: 'חיננית', rings: () => { const r = [circle(25, 25, 8)]; for (let k = 0; k < 12; k++) { const t = (k / 12) * TAU; r.push(ellipse(25 + 15 * Math.cos(t), 25 + 15 * Math.sin(t), 9.5, 3.4, 48, t)); } return r; } },
  { id: 'leaf', label: 'עלה', rings: () => [Array.from({ length: 100 }, (_, i) => { const t = (i / 100) * TAU; return { x: 25 + 24 * Math.cos(t), y: 25 + 11 * Math.sin(t) * Math.abs(Math.cos(t / 2)) * 1.6 }; })] },
  { id: 'crown', label: 'כתר', rings: () => [[{ x: 2, y: 40 }, { x: 0, y: 12 }, { x: 13, y: 25 }, { x: 25, y: 6 }, { x: 37, y: 25 }, { x: 50, y: 12 }, { x: 48, y: 40 }], circle(0, 10, 3, 32), circle(25, 4, 3, 32), circle(50, 10, 3, 32)] },
  { id: 'pomegranate', label: 'רימון', rings: () => [circle(25, 29, 19), [{ x: 18, y: 13 }, { x: 16, y: 4 }, { x: 21, y: 8 }, { x: 25, y: 1 }, { x: 29, y: 8 }, { x: 34, y: 4 }, { x: 32, y: 13 }]] },
  { id: 'moon', label: 'ירח', rings: () => [circle(25, 25, 24)], sub: () => [circle(34, 19, 19)] },
  { id: 'cloud', label: 'ענן', rings: () => [circle(15, 28, 11), circle(27, 21, 14), circle(38, 28, 11), roundRect(8, 26, 44, 39, 6)] },
  { id: 'drop', label: 'טיפה', rings: () => [Array.from({ length: 120 }, (_, i) => { const t = (i / 120) * TAU; return { x: 25 + 27 * Math.sin(t) * Math.sin(t / 2), y: 25 - 24 * Math.cos(t) }; })] },
  { id: 'diamond', label: 'יהלום', rings: () => [[{ x: 25, y: 0 }, { x: 50, y: 25 }, { x: 25, y: 50 }, { x: 0, y: 25 }]] },
  { id: 'paw', label: 'כף רגל', rings: () => [ellipse(25, 33, 13, 11), ellipse(11, 17, 5, 6.5, 48, -0.3), ellipse(20, 10, 5, 6.5), ellipse(30, 10, 5, 6.5), ellipse(39, 17, 5, 6.5, 48, 0.3)] },
  { id: 'banner', label: 'סרט כותרת', rings: () => [[{ x: 0, y: 8 }, { x: 50, y: 8 }, { x: 45, y: 17 }, { x: 50, y: 26 }, { x: 0, y: 26 }, { x: 5, y: 17 }]] },
];

/** Build stitchable shapes for a library item at a given width (mm). */
export function libraryShapes(id, widthMm = 50) {
  const item = LIBRARY.find((x) => x.id === id) || LIBRARY[0];
  let rings = item.rings();
  let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity;
  for (const r of rings) for (const p of r) { minX = Math.min(minX, p.x); minY = Math.min(minY, p.y); maxX = Math.max(maxX, p.x); maxY = Math.max(maxY, p.y); }
  const k = widthMm / Math.max(1e-6, maxX - minX);
  rings = rings.map((r) => r.map((p) => ({ x: (p.x - (minX + maxX) / 2) * k, y: (p.y - (minY + maxY) / 2) * k })));
  const res = Math.max(0.05, widthMm / 900);
  const { mask, w, h, ox, oy } = rasterizeRings(rings, res, 3, 'nonzero');
  if (item.sub) {
    // subtract cut-out shapes (e.g. the crescent moon) on the same grid
    const sub = item.sub().map((r) => r.map((p) => ({ x: (p.x - (minX + maxX) / 2) * k, y: (p.y - (minY + maxY) / 2) * k })));
    for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) {
      const i = y * w + x;
      if (!mask[i]) continue;
      const px = ox + (x + 0.5) * res, py = oy + (y + 0.5) * res;
      if (sub.some((r) => pointInRing(px, py, r))) mask[i] = 0;
    }
  }
  return shapesFromMask(mask, w, h, res, ox, oy, { smooth: 2, minAreaMm2: 0.5, simplify: 0.4 });
}

/** SVG path preview of a library item (for the picker). */
export function librarySvgPath(id) {
  const item = LIBRARY.find((x) => x.id === id);
  const rs = item.rings().concat(item.sub ? item.sub().map(rev) : []);
  return rs.map((r) => 'M' + r.map((p) => `${p.x.toFixed(1)},${p.y.toFixed(1)}`).join('L') + 'Z').join('');
}

/**
 * A band along the outline of shapes (for satin borders / appliqué cover stitch).
 * widthMm: band width; inside: fraction of the band that lies inside the shape.
 */
export function outlineBand(shapes, widthMm = 3, inside = 0.6) {
  const rings = [];
  for (const s of shapes) rings.push(s.outer, ...s.holes);
  const res = Math.max(0.05, Math.min(0.12, widthMm / 30));
  const pad = Math.ceil((widthMm * 1.2) / res) * 2 + 6;
  const { mask, w, h, ox, oy } = rasterizeRings(rings, res, pad, 'evenodd');
  const din = distanceTransform(mask, w, h);
  const inv = new Uint8Array(w * h);
  for (let i = 0; i < inv.length; i++) inv[i] = mask[i] ? 0 : 1;
  const dout = distanceTransform(inv, w, h);
  const band = new Uint8Array(w * h);
  const a = (widthMm * inside) / res, b = (widthMm * (1 - inside)) / res;
  const edge = Math.ceil(b) + 2; // the image border counts as 'outside' in the distance transform – ignore it
  for (let i = 0; i < band.length; i++) {
    const x = i % w, y = (i - x) / w;
    const nearBorder = x < edge || y < edge || x >= w - edge || y >= h - edge;
    band[i] = mask[i] ? (din[i] <= a ? 1 : 0) : (!nearBorder && dout[i] <= b ? 1 : 0);
  }
  const out = shapesFromMask(band, w, h, res, ox, oy, { smooth: 2, minAreaMm2: 0.3, simplify: 0.4 });
  out.forEach((s) => (s.width = widthMm));
  return out;
}

/** Inset outline (for appliqué placement / tack-down lines). */
export function insetShapes(shapes, mm) {
  const rings = [];
  for (const s of shapes) rings.push(s.outer, ...s.holes);
  const res = 0.08;
  const { mask, w, h, ox, oy } = rasterizeRings(rings, res, 3, 'evenodd');
  const din = distanceTransform(mask, w, h);
  const m2 = new Uint8Array(w * h);
  for (let i = 0; i < m2.length; i++) m2[i] = mask[i] && din[i] * res > mm ? 1 : 0;
  return shapesFromMask(m2, w, h, res, ox, oy, { smooth: 2, minAreaMm2: 0.3, simplify: 0.4 });
}
