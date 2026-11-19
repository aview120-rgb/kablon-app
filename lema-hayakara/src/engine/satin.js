// Turning satin: stitches run across the stroke and follow its curve, like a
// professional digitizer does for lettering and narrow shapes.
// Method: rasterise the shape, thin it to a centre-line skeleton, walk every
// skeleton branch and, at small steps, cast rays perpendicular to the branch
// to find the two edges. Areas the satin columns miss (corners, junctions)
// are filled first with small tatami patches so the result has no gaps.
import { rasterizeRings, shapesFromMask } from './vector.js';
import { distanceTransform } from './raster.js';

// Zhang–Suen thinning (in place on a copy). Returns Uint8Array skeleton.
function thin(mask, w, h) {
  const img = Uint8Array.from(mask);
  const del = [];
  let changed = true;
  const P = (x, y) => (x < 0 || y < 0 || x >= w || y >= h ? 0 : img[y * w + x]);
  for (let iter = 0; changed && iter < 200; iter++) {
    changed = false;
    for (let step = 0; step < 2; step++) {
      del.length = 0;
      for (let y = 1; y < h - 1; y++) {
        for (let x = 1; x < w - 1; x++) {
          if (!img[y * w + x]) continue;
          const p2 = P(x, y - 1), p3 = P(x + 1, y - 1), p4 = P(x + 1, y), p5 = P(x + 1, y + 1);
          const p6 = P(x, y + 1), p7 = P(x - 1, y + 1), p8 = P(x - 1, y), p9 = P(x - 1, y - 1);
          const B = p2 + p3 + p4 + p5 + p6 + p7 + p8 + p9;
          if (B < 2 || B > 6) continue;
          const seq = [p2, p3, p4, p5, p6, p7, p8, p9, p2];
          let A = 0;
          for (let i = 0; i < 8; i++) if (!seq[i] && seq[i + 1]) A++;
          if (A !== 1) continue;
          if (step === 0) { if (p2 * p4 * p6 !== 0 || p4 * p6 * p8 !== 0) continue; }
          else if (p2 * p4 * p8 !== 0 || p2 * p6 * p8 !== 0) continue;
          del.push(y * w + x);
        }
      }
      if (del.length) { changed = true; for (const i of del) img[i] = 0; }
    }
  }
  return img;
}

const N8 = [[1, 0], [1, 1], [0, 1], [-1, 1], [-1, 0], [-1, -1], [0, -1], [1, -1]];

// Split the skeleton into branches (pixel paths) between end/junction points.
// Node detection uses the crossing number so diagonal staircases are not junctions.
function branches(skel, w, h) {
  const on = (x, y) => x >= 0 && y >= 0 && x < w && y < h && skel[y * w + x] === 1;
  const ring = [[1, 0], [1, 1], [0, 1], [-1, 1], [-1, 0], [-1, -1], [0, -1], [1, -1]];
  const cn = new Uint8Array(w * h);
  const pix = [];
  for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) {
    if (!skel[y * w + x]) continue;
    let t = 0;
    for (let k = 0; k < 8; k++) {
      const [ax, ay] = ring[k], [bx, by] = ring[(k + 1) % 8];
      if (!on(x + ax, y + ay) && on(x + bx, y + by)) t++;
    }
    cn[y * w + x] = t;
    pix.push(y * w + x);
  }
  const isNode = (i) => cn[i] !== 2;
  const visited = new Uint8Array(w * h);
  const nbrs = (i) => {
    const x = i % w, y = (i - x) / w, four = [], diag = [];
    for (const [dx, dy] of ring) if (on(x + dx, y + dy)) (dx && dy ? diag : four).push((y + dy) * w + x + dx);
    return four.concat(diag);
  };
  const paths = [];
  const walk = (start, first) => {
    const path = [start, first];
    if (isNode(first)) return path;
    visited[first] = 1;
    let prev = start, cur = first;
    for (let guard = 0; guard < w * h; guard++) {
      const cand = nbrs(cur).filter((q) => q !== prev && q !== start || (q === start && path.length > 3));
      let next = cand.find((q) => isNode(q) && q !== prev && path.length > 2) ?? cand.find((q) => !isNode(q) && !visited[q]);
      if (next === undefined) next = cand.find((q) => isNode(q) && q !== prev);
      if (next === undefined) break;
      path.push(next);
      if (isNode(next)) break;
      visited[next] = 1;
      prev = cur; cur = next;
    }
    return path;
  };
  for (const s of pix) {
    if (!isNode(s)) continue;
    for (const n0 of nbrs(s)) {
      if (!isNode(n0) && visited[n0]) continue;
      if (isNode(n0)) continue; // adjacent node pixels belong to the same junction
      const path = walk(s, n0);
      if (path.length >= 3) paths.push({ px: path, startNode: cn[s], endNode: cn[path[path.length - 1]], closed: false });
    }
  }
  // pure loops (every pixel cn==2, e.g. the letter O)
  for (const s of pix) {
    if (visited[s] || isNode(s)) continue;
    visited[s] = 1;
    const path = [s];
    let prev = -1, cur = s;
    for (let guard = 0; guard < w * h; guard++) {
      const next = nbrs(cur).find((q) => q !== prev && !visited[q]);
      if (next === undefined) break;
      visited[next] = 1; path.push(next); prev = cur; cur = next;
    }
    if (path.length > 6) paths.push({ px: path, startNode: 2, endNode: 2, closed: true });
  }
  return paths;
}

function smoothPts(pts, rad, closed) {
  const n = pts.length;
  if (n < 3) return pts;
  const out = [];
  for (let i = 0; i < n; i++) {
    let sx = 0, sy = 0, c = 0;
    for (let k = -rad; k <= rad; k++) {
      let j = i + k;
      if (closed) j = ((j % n) + n) % n; else j = Math.max(0, Math.min(n - 1, j));
      sx += pts[j].x; sy += pts[j].y; c++;
    }
    out.push({ x: sx / c, y: sy / c });
  }
  return out;
}

function resample(pts, step, closed) {
  const src = closed ? pts.concat([pts[0]]) : pts;
  const out = [src[0]];
  let carry = 0;
  for (let i = 1; i < src.length; i++) {
    const a = src[i - 1], b = src[i];
    const L = Math.hypot(b.x - a.x, b.y - a.y);
    if (!L) continue;
    let t = step - carry;
    while (t <= L) { out.push({ x: a.x + ((b.x - a.x) * t) / L, y: a.y + ((b.y - a.y) * t) / L }); t += step; }
    carry = L - (t - step);
  }
  if (!closed) {
    const l = src[src.length - 1], q = out[out.length - 1];
    if (Math.hypot(l.x - q.x, l.y - q.y) > step * 0.3) out.push(l);
  }
  return out;
}

/**
 * Turning satin for a narrow shape.
 * Returns { runs: [[pts]...], gaps: [shapes] } – gaps should be filled before the runs.
 */
export function satinShape(shape, o) {
  const width = shape.width || 3;
  const res = Math.max(0.035, Math.min(0.09, width / 26));
  const rings = [shape.outer, ...shape.holes];
  const { mask, w, h, ox, oy } = rasterizeRings(rings, res, 3, 'evenodd');
  let count = 0;
  for (let i = 0; i < mask.length; i++) count += mask[i];
  if (count < 12) return null;
  const dt = distanceTransform(mask, w, h);
  const inside = (x, y) => {
    const px = Math.floor((x - ox) / res), py = Math.floor((y - oy) / res);
    return px >= 0 && py >= 0 && px < w && py < h && mask[py * w + px] === 1;
  };
  const dtAt = (x, y) => {
    const px = Math.min(w - 1, Math.max(0, Math.floor((x - ox) / res))), py = Math.min(h - 1, Math.max(0, Math.floor((y - oy) / res)));
    return dt[py * w + px] * res;
  };
  let skel = thin(mask, w, h);
  let paths = branches(skel, w, h);
  // prune short spurs (branches ending in a tip shorter than the local half-width)
  for (let pass = 0; pass < 3; pass++) {
    let pruned = false;
    const keep = [];
    for (const p of paths) {
      const tip = p.startNode <= 1 || p.endNode <= 1;
      const junction = p.startNode >= 3 || p.endNode >= 3;
      if (tip && junction) {
        const j = p.startNode >= 3 ? p.px[0] : p.px[p.px.length - 1];
        const jd = dt[j];
        if (p.px.length < jd * 1.25) { pruned = true; continue; }
      }
      keep.push(p);
    }
    if (!pruned) break;
    skel = new Uint8Array(w * h);
    for (const p of keep) for (const i of p.px) skel[i] = 1;
    paths = branches(skel, w, h);
  }
  const toMm = (i) => ({ x: ox + ((i % w) + 0.5) * res, y: oy + (Math.floor(i / w) + 0.5) * res });
  const step = Math.max(0.12, (o.satinSpacing || 0.4) / 2);
  const pc = o.pullComp ?? 0.2;
  const cov = new Uint8Array(w * h);
  const runs = [];
  const underlays = [];
  const cols = [];
  const fillQuad = (a, b, c, d) => {
    // rasterise convex quad a-b-c-d onto coverage grid
    const q = [a, b, c, d].map((p) => ({ x: (p.x - ox) / res, y: (p.y - oy) / res }));
    const minY = Math.max(0, Math.floor(Math.min(...q.map((p) => p.y)))), maxY = Math.min(h - 1, Math.ceil(Math.max(...q.map((p) => p.y))));
    for (let y = minY; y <= maxY; y++) {
      const yc = y + 0.5;
      const xs = [];
      for (let i = 0; i < 4; i++) {
        const p = q[i], r = q[(i + 1) % 4];
        if ((p.y <= yc && r.y > yc) || (r.y <= yc && p.y > yc)) xs.push(p.x + ((yc - p.y) / (r.y - p.y)) * (r.x - p.x));
      }
      if (xs.length < 2) continue;
      const x0 = Math.max(0, Math.floor(Math.min(...xs) - 0.5)), x1 = Math.min(w - 1, Math.ceil(Math.max(...xs) + 0.5));
      for (let x = x0; x <= x1; x++) cov[y * w + x] = 1;
    }
  };
  for (const p of paths) {
    if (p.px.length < 2 && !p.closed) continue;
    let pts = p.px.map(toMm);
    pts = smoothPts(pts, Math.max(2, Math.round((width * 0.45) / res)), p.closed);
    pts = smoothPts(pts, Math.max(2, Math.round((width * 0.3) / res)), p.closed);
    pts = resample(pts, step, p.closed);
    if (pts.length < 2) continue;
    // extend open ends along the tangent so stroke tips are covered
    if (!p.closed) {
      const ext = (endIdx, prevIdx) => {
        const e = pts[endIdx], q = pts[prevIdx];
        let dx = e.x - q.x, dy = e.y - q.y;
        const L = Math.hypot(dx, dy) || 1; dx /= L; dy /= L;
        const add = [];
        const maxExt = Math.max(0.3, dtAt(e.x, e.y) * 1.6);
        for (let t = step; t <= maxExt; t += step) {
          const x = e.x + dx * t, y = e.y + dy * t;
          if (!inside(x, y)) break;
          add.push({ x, y });
        }
        return add;
      };
      const k = Math.min(pts.length - 1, Math.max(4, Math.round((width * 0.6) / step)));
      const head = ext(0, k).reverse();
      const tail = ext(pts.length - 1, pts.length - 1 - k);
      pts = head.concat(pts, tail);
    }
    const n = pts.length;
    const left = [], right = [];
    for (let i = 0; i < n; i++) {
      const K = Math.max(3, Math.round((width * 0.55) / step));
      const a = pts[p.closed ? (((i - K) % n) + n) % n : Math.max(0, i - K)];
      const b = pts[p.closed ? (i + K) % n : Math.min(n - 1, i + K)];
      let tx = b.x - a.x, ty = b.y - a.y;
      const L = Math.hypot(tx, ty) || 1; tx /= L; ty /= L;
      const nx = -ty, ny = tx;
      const c = pts[i];
      const maxRay = Math.max(0.45, dtAt(c.x, c.y) * 1.35);
      const march = (sx) => {
        let t = 0;
        const st = res * 0.5;
        while (t < maxRay && inside(c.x + nx * sx * (t + st), c.y + ny * sx * (t + st))) t += st;
        return t;
      };
      const tl = march(1), tr = march(-1);
      left.push({ x: c.x + nx * (tl + pc), y: c.y + ny * (tl + pc), d: tl });
      right.push({ x: c.x - nx * (tr + pc), y: c.y - ny * (tr + pc), d: tr });
    }
    cols.push({ p, pts, left, right });
  }
  // typical stroke width of this shape; much wider spots (serif balls, blobs,
  // sharp corners) get a regular fill instead of a fan of satin stitches
  const widths = [];
  for (const c of cols) for (let i = 0; i < c.left.length; i++) widths.push(c.left[i].d + c.right[i].d);
  widths.sort((a, b) => a - b);
  const med = widths.length ? widths[Math.floor(widths.length / 2)] : width;
  const maxW = Math.min(Math.max(med * 2.3, med + 2), (o.satinMax || 7.5) * 1.15);
  for (const { pts, left, right } of cols) {
    const n = pts.length;
    let zig = [];
    const flush = () => { if (zig.length >= 4) runs.push(zig.map(({ x, y }) => ({ x, y }))); zig = []; };
    let prevOk = false;
    for (let i = 0; i < n; i++) {
      const wd = left[i].d + right[i].d;
      // a sharp change of ray direction between neighbours makes a fan – skip those too
      let turn = false;
      if (i > 0) {
        const ax = left[i].x - right[i].x, ay = left[i].y - right[i].y, bx = left[i - 1].x - right[i - 1].x, by = left[i - 1].y - right[i - 1].y;
        const cosA = (ax * bx + ay * by) / ((Math.hypot(ax, ay) * Math.hypot(bx, by)) || 1);
        turn = cosA < 0.5;
      }
      if (wd < 0.15 || wd > maxW || turn) { prevOk = false; flush(); continue; }
      zig.push(i % 2 === 0 ? left[i] : right[i]);
      if (prevOk) fillQuad(left[i - 1], right[i - 1], right[i], left[i]);
      prevOk = true;
    }
    flush();
    if (o.underlay !== false && width > 1.2 && n > 4) underlays.push(resample(pts, 2, false));
  }
  if (false) {
    const zig = [];
  }
  if (!runs.length) return null;
  // gaps: mask pixels not covered by any column
  const gapMask = new Uint8Array(w * h);
  let gapCount = 0;
  for (let i = 0; i < w * h; i++) if (mask[i] && !cov[i]) { gapMask[i] = 1; gapCount++; }
  let gaps = [];
  // morphological opening: drop thin slivers along column edges, keep real blobs
  if (gapCount) {
    const gdt = distanceTransform(gapMask, w, h);
    const r = Math.max(1.5, 0.3 / res);
    const core = new Uint8Array(w * h);
    for (let i = 0; i < w * h; i++) core[i] = gdt[i] >= r ? 1 : 0;
    // grow the cores back (dilate by r + a little overlap into the columns)
    const inv = new Uint8Array(w * h);
    for (let i = 0; i < w * h; i++) inv[i] = core[i] ? 0 : 1;
    const odt = distanceTransform(inv, w, h);
    gapCount = 0;
    for (let i = 0; i < w * h; i++) { gapMask[i] = mask[i] && odt[i] <= r + 1.5 / 1 ? 1 : 0; gapCount += gapMask[i]; }
  }
  if (gapCount * res * res > 0.15) {
    gaps = shapesFromMask(gapMask, w, h, res, ox, oy, { smooth: 1, minAreaMm2: 0.9, simplify: 0.5 });
  }
  return { runs, underlays, gaps };
}
