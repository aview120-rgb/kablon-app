// Photo embroidery: one-colour "line shading" (like photo-stitch techniques)
// where darker areas get wider zig-zags, plus multi-colour photo digitizing.
import { resizeImage, smoothImage } from './raster.js';
import { rotatePoint } from './geom.js';
import { newId } from './design.js';

/**
 * Line-shading photo stitch.
 * opts: { widthMm, rowSpacing (mm), step (mm), angle (deg), contrast, invert, color }
 */
export function photoLines(img, opts = {}) {
  const o = { widthMm: 100, rowSpacing: 1.1, step: 0.55, angle: 0, contrast: 1.2, gamma: 1, invert: false, color: '#202020', ...opts };
  const aspect = img.height / img.width;
  const heightMm = o.widthMm * aspect;
  // sample grid at ~0.25mm
  const gw = Math.min(900, Math.max(60, Math.round(o.widthMm / 0.25)));
  const gh = Math.max(10, Math.round(gw * aspect));
  const small = smoothImage(resizeImage(img, gw, gh), 1, 30);
  const mmPerPx = o.widthMm / gw;
  const gray = new Float32Array(gw * gh);
  // histogram stretch for contrast
  let lo = 255, hi = 0;
  for (let i = 0; i < gw * gh; i++) {
    const a = small.data[i * 4 + 3];
    const v = 0.299 * small.data[i * 4] + 0.587 * small.data[i * 4 + 1] + 0.114 * small.data[i * 4 + 2];
    gray[i] = a < 128 ? -1 : v;
    if (a >= 128) { lo = Math.min(lo, v); hi = Math.max(hi, v); }
  }
  const span = Math.max(1, hi - lo);
  const darkness = (x, y) => {
    const px = Math.floor(x / mmPerPx), py = Math.floor(y / mmPerPx);
    if (px < 0 || py < 0 || px >= gw || py >= gh) return -1;
    const g = gray[py * gw + px];
    if (g < 0) return -1;
    let d = 1 - (g - lo) / span;
    if (o.invert) d = 1 - d;
    d = Math.min(1, Math.max(0, (d - 0.5) * o.contrast + 0.5));
    return Math.pow(d, o.gamma);
  };
  const ang = (o.angle * Math.PI) / 180;
  const cx = o.widthMm / 2, cy = heightMm / 2;
  const R = Math.hypot(o.widthMm, heightMm) / 2 + 2;
  const points = [];
  let row = 0;
  let needJump = true;
  for (let v = -R; v <= R; v += o.rowSpacing, row++) {
    const fwd = row % 2 === 0;
    const samples = [];
    for (let u = -R; u <= R; u += o.step) samples.push(u);
    if (!fwd) samples.reverse();
    let up = true;
    let inRun = false;
    needJump = true;
    for (const u of samples) {
      const p = rotatePoint({ x: u, y: v }, ang);
      const x = p.x + cx, y = p.y + cy;
      const d = darkness(x, y);
      if (d < 0.06) {
        if (inRun) { inRun = false; needJump = true; }
        continue;
      }
      const amp = d * o.rowSpacing * 0.48;
      const off = rotatePoint({ x: 0, y: up ? -amp : amp }, ang);
      up = !up;
      const pt = { x: x + off.x, y: y + off.y };
      if (needJump) { pt.j = true; pt.t = points.length > 0; needJump = false; }
      points.push(pt);
      inRun = true;
    }
    if (inRun) {
      // continue into next row without trimming if the next row starts nearby
      inRun = false;
    }
  }
  // remove trims where the jump is short (keeps the machine running smoothly)
  for (let i = 1; i < points.length; i++) {
    const p = points[i];
    if (p.j) {
      const q = points[i - 1];
      const d = Math.hypot(p.x - q.x, p.y - q.y);
      if (d < 2.5) { delete p.j; delete p.t; } else if (d < 5) p.t = false;
    }
  }
  return {
    objects: [{ id: newId(), type: 'stitches', name: 'צילום בקווים', color: o.color, points, visible: true, photo: true }],
    widthMm: o.widthMm, heightMm,
  };
}
