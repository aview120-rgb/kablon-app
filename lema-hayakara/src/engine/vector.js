// Shared raster <-> vector helpers: nonzero polygon rasteriser and mask → shapes.
import { traceMask, distanceTransform, components } from './raster.js';
import { ringsToShapes, simplifyRing, densifyRing, smoothRing, shapeArea, ringBounds, pointInShape } from './geom.js';

/** Rasterise rings (mm) with the nonzero rule. Returns mask with origin (ox, oy) in mm. */
export function rasterizeRings(rings, mmPerPx, padPx = 2) {
  let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity;
  for (const r of rings) for (const p of r) { minX = Math.min(minX, p.x); minY = Math.min(minY, p.y); maxX = Math.max(maxX, p.x); maxY = Math.max(maxY, p.y); }
  const ox = minX - padPx * mmPerPx, oy = minY - padPx * mmPerPx;
  const w = Math.ceil((maxX - minX) / mmPerPx) + padPx * 2 + 1;
  const h = Math.ceil((maxY - minY) / mmPerPx) + padPx * 2 + 1;
  const mask = new Uint8Array(w * h);
  const edges = [];
  for (const r of rings) for (let i = 0; i < r.length; i++) {
    const a = r[i], b = r[(i + 1) % r.length];
    const ay = (a.y - oy) / mmPerPx, by = (b.y - oy) / mmPerPx;
    if (ay === by) continue;
    const ax = (a.x - ox) / mmPerPx, bx = (b.x - ox) / mmPerPx;
    edges.push(ay < by ? [ax, ay, bx, by, 1] : [bx, by, ax, ay, -1]);
  }
  for (let y = 0; y < h; y++) {
    const yc = y + 0.5;
    const xs = [];
    for (const e of edges) if (e[1] <= yc && yc < e[3]) xs.push([e[0] + ((yc - e[1]) / (e[3] - e[1])) * (e[2] - e[0]), e[4]]);
    if (!xs.length) continue;
    xs.sort((p, q) => p[0] - q[0]);
    let wind = 0;
    for (let i = 0; i < xs.length - 1; i++) {
      wind += xs[i][1];
      if (wind !== 0) {
        const x0 = Math.max(0, Math.ceil(xs[i][0] - 0.5)), x1 = Math.min(w - 1, Math.floor(xs[i + 1][0] - 0.5));
        for (let x = x0; x <= x1; x++) mask[y * w + x] = 1;
      }
    }
  }
  return { mask, w, h, ox, oy };
}

/** Trace a binary mask into smooth shapes in mm, each with an estimated stroke width. */
export function shapesFromMask(mask, w, h, mmPerPx, ox = 0, oy = 0, { smooth = 2, minAreaMm2 = 0.3, simplify = 0.3 } = {}) {
  const rings = traceMask(mask, w, h).map((r) => {
    let pts = densifyRing(r, 1);
    if (smooth) { pts = smoothRing(pts, smooth); pts = smoothRing(pts, 1, -2); }
    pts = pts.map((p) => ({ x: ox + p.x * mmPerPx, y: oy + p.y * mmPerPx }));
    return simplifyRing(pts, mmPerPx * simplify);
  });
  const shapes = ringsToShapes(rings).filter((s) => shapeArea(s) > minAreaMm2);
  const dt = distanceTransform(mask, w, h);
  const { comp } = components(Uint8Array.from(mask), w, h);
  const peaks = new Map();
  for (let i = 0; i < mask.length; i++) {
    if (!mask[i]) continue;
    const c = comp[i], cur = peaks.get(c);
    if (!cur || dt[i] > cur.d) peaks.set(c, { d: dt[i], i });
  }
  const pk = [...peaks.values()].map((v) => ({ d: v.d, x: ox + ((v.i % w) + 0.5) * mmPerPx, y: oy + (Math.floor(v.i / w) + 0.5) * mmPerPx }));
  for (const s of shapes) {
    const b = ringBounds(s.outer);
    let best = 0;
    for (const p of pk) {
      if (p.x < b.minX || p.x > b.maxX || p.y < b.minY || p.y > b.maxY) continue;
      if (p.d > best && pointInShape(p.x, p.y, s)) best = p.d;
    }
    s.width = best ? best * 2 * mmPerPx : null;
  }
  return shapes;
}
