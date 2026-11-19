// Stitched background / frame behind a design (like a woven label or a tallit bag):
// a fill with "knock-out" holes under the letters plus an optional satin border.
import { rasterizeRings, shapesFromMask } from './vector.js';
import { distanceTransform } from './raster.js';
import { ringBounds } from './geom.js';

function outline(kind, b, margin, radius) {
  const x0 = b.minX - margin, y0 = b.minY - margin, x1 = b.maxX + margin, y1 = b.maxY + margin;
  const cx = (x0 + x1) / 2, cy = (y0 + y1) / 2, w = x1 - x0, h = y1 - y0;
  const pts = [];
  if (kind === 'ellipse' || kind === 'circle') {
    let rx = (w / 2) * 1.2, ry = (h / 2) * 1.25;
    if (kind === 'circle') rx = ry = Math.max(rx, ry, Math.hypot(w, h) / 2);
    for (let i = 0; i < 120; i++) { const t = (i / 120) * Math.PI * 2; pts.push({ x: cx + rx * Math.cos(t), y: cy + ry * Math.sin(t) }); }
    return pts;
  }
  const r = kind === 'rounded' ? Math.min(radius ?? Math.min(w, h) * 0.22, w / 2, h / 2) : kind === 'pill' ? h / 2 : Math.min(radius ?? 0, w / 2, h / 2);
  const corner = (ccx, ccy, a0) => { for (let k = 0; k <= 10; k++) { const t = a0 + (k / 10) * (Math.PI / 2); pts.push({ x: ccx + r * Math.cos(t), y: ccy + r * Math.sin(t) }); } };
  if (r > 0.01) {
    corner(x1 - r, y0 + r, -Math.PI / 2);
    corner(x1 - r, y1 - r, 0);
    corner(x0 + r, y1 - r, Math.PI / 2);
    corner(x0 + r, y0 + r, Math.PI);
  } else pts.push({ x: x0, y: y0 }, { x: x1, y: y0 }, { x: x1, y: y1 }, { x: x0, y: y1 });
  return pts;
}

/**
 * spec: { shape: 'rect'|'rounded'|'pill'|'ellipse'|'circle', margin (mm), color,
 *         border: bool, borderColor, borderWidth (mm), knockout: bool }
 * Returns new objects to put *before* the design: [fill, border?]
 */
export function makeBackground(objects, spec) {
  const shapes = [];
  for (const o of objects) if (o.shapes && o.visible !== false && !o.isBackground) shapes.push(...o.shapes);
  if (!shapes.length) return [];
  let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity;
  for (const s of shapes) { const b = ringBounds(s.outer); minX = Math.min(minX, b.minX); minY = Math.min(minY, b.minY); maxX = Math.max(maxX, b.maxX); maxY = Math.max(maxY, b.maxY); }
  const bwr = spec.border ? Math.max(1.5, spec.borderWidth ?? 3) : 0;
  const ring = outline(spec.shape || 'rounded', { minX, minY, maxX, maxY }, spec.margin ?? 6, spec.shape === 'rect' ? Math.max(1.2, bwr * 0.9) : undefined);
  const res = Math.max(0.08, Math.min(0.2, Math.max(maxX - minX, maxY - minY) / 1500));
  const bgR = rasterizeRings([ring], res, 4);
  const { w, h, ox, oy } = bgR;
  const mask = bgR.mask;
  const out = [];
  const bw = spec.border ? Math.max(1.5, spec.borderWidth ?? 3) : 0;
  // border band = outer ring minus inset (distance transform of the background)
  const dt = distanceTransform(mask, w, h);
  if (spec.knockout !== false) {
    // letters cut out of the fill, shrunk 0.35mm so the fill tucks under them (no gaps)
    const lr = [];
    for (const s of shapes) lr.push(s.outer, ...s.holes);
    // rasterise letters on the same grid
    const L = new Uint8Array(w * h);
    const tmp = rasterizeRings(lr, res, 0, 'evenodd');
    for (let y = 0; y < tmp.h; y++) for (let x = 0; x < tmp.w; x++) {
      if (!tmp.mask[y * tmp.w + x]) continue;
      const gx = Math.round((tmp.ox + x * res - ox) / res), gy = Math.round((tmp.oy + y * res - oy) / res);
      if (gx >= 0 && gy >= 0 && gx < w && gy < h) L[gy * w + gx] = 1;
    }
    const ldt = distanceTransform(L, w, h);
    const shrink = 0.35 / res;
    for (let i = 0; i < mask.length; i++) if (L[i] && ldt[i] > shrink) mask[i] = 0;
  }
  const fillMask = new Uint8Array(w * h);
  for (let i = 0; i < mask.length; i++) fillMask[i] = mask[i] && (!bw || dt[i] * res > bw - 0.4) ? 1 : 0;
  const fillShapes = shapesFromMask(fillMask, w, h, res, ox, oy, { smooth: 1, minAreaMm2: 0.5, simplify: 0.6 });
  out.push({ id: 'bg_fill', type: 'region', isBackground: true, name: 'רקע', color: spec.color || '#e8d9b5', shapes: fillShapes, stitch: { kind: 'fill', angle: spec.angle != null ? spec.angle : Math.PI / 2 }, visible: true });
  if (bw) {
    const band = new Uint8Array(w * h);
    for (let i = 0; i < mask.length; i++) band[i] = dt[i] > 0 && dt[i] * res <= bw ? 1 : 0;
    const bandShapes = shapesFromMask(band, w, h, res, ox, oy, { smooth: 1, minAreaMm2: 0.5, simplify: 0.4 });
    bandShapes.forEach((s) => (s.width = bw));
    out.push({ id: 'bg_border', type: 'region', isBackground: true, name: 'מסגרת', color: spec.borderColor || '#1f4e79', shapes: bandShapes, stitch: { kind: 'satin' }, visible: true });
  }
  return out;
}
