// Stitch generation: tatami fill, satin, running stitch, underlay, travel.
// Output: arrays of points {x, y, j?: true (jump), t?: true (trim before)} in mm.
import { rotatePoint, pointInShape, offsetRing, ringArea, dist, ringLength } from './geom.js';
import { satinShape } from './satin.js';

export const DEFAULTS = {
  fillSpacing: 0.42,      // mm between fill rows
  stitchLength: 3.5,      // mm max length in tatami rows
  satinSpacing: 0.4,      // mm between satin zigzags
  satinMax: 7.5,          // mm, longer columns are split into tatami
  pullComp: 0.2,          // mm added on each side of a row
  underlay: true,
  runLength: 2.2,         // mm running stitch length
  trimDistance: 4,        // jumps longer than this get a trim
  minStitch: 0.3,         // mm, shorter stitches are dropped
};

// Scanline intersection of a (rotated) shape. Returns rows: [[xa,xb],...] per y.
function scanRows(rings, spacing) {
  let minY = Infinity, maxY = -Infinity;
  for (const r of rings) for (const p of r) { if (p.y < minY) minY = p.y; if (p.y > maxY) maxY = p.y; }
  const rows = [];
  const edges = [];
  for (const r of rings) {
    for (let i = 0; i < r.length; i++) {
      const a = r[i], b = r[(i + 1) % r.length];
      if (a.y === b.y) continue;
      edges.push(a.y < b.y ? [a.x, a.y, b.x, b.y] : [b.x, b.y, a.x, a.y]);
    }
  }
  edges.sort((p, q) => p[1] - q[1]);
  const count = Math.floor((maxY - minY) / spacing);
  const off = (maxY - minY - count * spacing) / 2;
  let ei = 0;
  const active = [];
  for (let k = 0; k <= count; k++) {
    const y = minY + off + k * spacing;
    while (ei < edges.length && edges[ei][1] <= y) active.push(edges[ei++]);
    const xs = [];
    for (let i = active.length - 1; i >= 0; i--) {
      const e = active[i];
      if (e[3] <= y) { active.splice(i, 1); continue; }
      if (e[1] <= y && y < e[3]) xs.push(e[0] + ((y - e[1]) / (e[3] - e[1])) * (e[2] - e[0]));
    }
    xs.sort((a, b) => a - b);
    const segs = [];
    for (let i = 0; i + 1 < xs.length; i += 2) if (xs[i + 1] - xs[i] > 0.05) segs.push([xs[i], xs[i + 1]]);
    rows.push({ y, segs });
  }
  return rows;
}

// Group row segments into blocks that can be sewn back and forth continuously.
function buildBlocks(rows) {
  const blocks = [];
  let open = []; // {block, seg}
  for (let k = 0; k < rows.length; k++) {
    const segs = rows[k].segs;
    const overlapsPrev = segs.map((s) => open.filter((o) => o.seg[0] < s[1] && s[0] < o.seg[1]));
    const nextOpen = [];
    const claimed = new Set();
    for (let i = 0; i < segs.length; i++) {
      const s = segs[i];
      const cands = overlapsPrev[i];
      if (cands.length === 1) {
        const o = cands[0];
        // o must overlap only this segment in the current row
        const otherCount = segs.filter((t) => o.seg[0] < t[1] && t[0] < o.seg[1]).length;
        if (otherCount === 1 && !claimed.has(o.block)) {
          o.block.rows.push({ y: rows[k].y, seg: s });
          claimed.add(o.block);
          nextOpen.push({ block: o.block, seg: s });
          continue;
        }
      }
      const b = { rows: [{ y: rows[k].y, seg: s }] };
      blocks.push(b);
      nextOpen.push({ block: b, seg: s });
    }
    open = nextOpen;
  }
  return blocks;
}

function tatamiRow(xa, xb, y, k, len, forward) {
  const pts = [];
  const phase = ((k % 3) * len) / 3;
  const margin = Math.min(0.8, len / 3);
  const first = Math.ceil((xa + margin - phase) / len);
  const xs = [xa];
  for (let n = first; ; n++) {
    const x = phase + n * len;
    if (x > xb - margin) break;
    xs.push(x);
  }
  xs.push(xb);
  if (!forward) xs.reverse();
  for (const x of xs) pts.push({ x, y });
  return pts;
}

/**
 * Fill a shape with parallel rows at `angle` (radians).
 * mode: 'tatami' | 'satin' | 'auto'
 * Returns an array of "runs": each run is a list of points sewn continuously;
 * runs need to be connected by travel/jumps.
 */
export function fillShape(shape, opt = {}) {
  const o = { ...DEFAULTS, ...opt };
  const angle = o.angle ?? 0;
  const spacing = o.spacing ?? (o.mode === 'satin' ? o.satinSpacing : o.fillSpacing);
  const rings = [shape.outer, ...shape.holes].map((r) => r.map((p) => rotatePoint(p, -angle)));
  const rows = scanRows(rings, spacing);
  const blocks = buildBlocks(rows);
  const runs = [];
  const pc = o.pullComp;
  for (const b of blocks) {
    const segLens = b.rows.map((r) => r.seg[1] - r.seg[0]);
    const satin = o.mode === 'satin' || (o.mode === 'auto' && Math.max(...segLens) <= o.satinMax);
    const pts = [];
    b.rows.forEach((r, i) => {
      const xa = r.seg[0] - (r.seg[1] - r.seg[0] > 0.6 ? pc : 0);
      const xb = r.seg[1] + (r.seg[1] - r.seg[0] > 0.6 ? pc : 0);
      const fwd = i % 2 === 0;
      if (satin && xb - xa <= o.satinMax * 1.6) {
        // satin: needle alternates left/right edge every row
        pts.push({ x: fwd ? xa : xb, y: r.y });
        pts.push({ x: fwd ? xb : xa, y: r.y + spacing / 2 });
      } else {
        const k = Math.round(r.y / spacing);
        pts.push(...tatamiRow(xa, xb, r.y, k, o.stitchLength, fwd));
      }
    });
    runs.push(pts.map((p) => rotatePoint(p, angle)));
  }
  return runs;
}

// Running stitch along a polyline with a fixed length.
export function runLine(pts, len, closed = false) {
  const src = closed ? pts.concat([pts[0]]) : pts;
  if (src.length < 2) return src.slice();
  const out = [{ ...src[0] }];
  let carry = 0;
  for (let i = 1; i < src.length; i++) {
    const a = src[i - 1], b = src[i];
    const L = dist(a, b);
    if (L < 1e-9) continue;
    let t = len - carry;
    while (t < L - 1e-6) {
      out.push({ x: a.x + ((b.x - a.x) * t) / L, y: a.y + ((b.y - a.y) * t) / L });
      t += len;
    }
    carry = L - (t - len);
    // always include sharp corners
    const c = src[i + 1];
    if (c) {
      const ux = (b.x - a.x) / L, uy = (b.y - a.y) / L;
      const L2 = dist(b, c) || 1;
      const vx = (c.x - b.x) / L2, vy = (c.y - b.y) / L2;
      if (ux * vx + uy * vy < 0.7) { out.push({ ...b }); carry = 0; }
    }
  }
  const last = src[src.length - 1];
  if (dist(out[out.length - 1], last) > 0.25) out.push({ ...last });
  return out;
}

// Triple ("bean") running stitch: each stitch is sewn forward-back-forward.
export function beanLine(pts, len, closed = false) {
  const base = runLine(pts, len, closed);
  const out = [base[0]];
  for (let i = 1; i < base.length; i++) out.push(base[i], base[i - 1], base[i]);
  return out;
}

// Rotate a closed ring so it starts at the vertex nearest to p.
export function startRingNear(ring, p) {
  let best = 0, bd = Infinity;
  for (let i = 0; i < ring.length; i++) {
    const d = (ring[i].x - p.x) ** 2 + (ring[i].y - p.y) ** 2;
    if (d < bd) { bd = d; best = i; }
  }
  return ring.slice(best).concat(ring.slice(0, best));
}

function segmentInside(a, b, shape, tol = 0) {
  const L = dist(a, b);
  const n = Math.max(2, Math.ceil(L / 0.7));
  for (let i = 1; i < n; i++) {
    const t = i / n;
    if (!pointInShape(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t, shape)) return false;
  }
  return true;
}

/**
 * Order runs greedily (each may be reversed) and join them into one stitch
 * list with travel stitches inside the shape or jumps.
 */
export function joinRuns(runs, start, shape, o, allowReverse = true) {
  const left = runs.filter((r) => r.length);
  const out = [];
  let cur = start;
  while (left.length) {
    let bi = 0, rev = false, bd = Infinity;
    for (let i = 0; i < left.length; i++) {
      const r = left[i];
      const d0 = cur ? dist(cur, r[0]) : 0;
      const d1 = cur ? dist(cur, r[r.length - 1]) : Infinity;
      if (d0 < bd) { bd = d0; bi = i; rev = false; }
      if (allowReverse && d1 < bd) { bd = d1; bi = i; rev = true; }
    }
    let r = left.splice(bi, 1)[0];
    if (rev) r = r.slice().reverse();
    if (cur && out.length) {
      const d = dist(cur, r[0]);
      if (d > 1.2) {
        if (shape && d < 25 && segmentInside(cur, r[0], shape)) {
          const tr = runLine([cur, r[0]], o.runLength);
          for (let i = 1; i < tr.length - 1; i++) out.push(tr[i]);
        } else {
          r = r.slice();
          r[0] = { ...r[0], j: true, t: d > o.trimDistance };
        }
      }
    }
    out.push(...r);
    cur = out[out.length - 1];
  }
  return out;
}

export function shapeWidth(shape) {
  // average stroke width estimate: for a long strip, 2*area/perimeter = width
  const per = ringLength(shape.outer) + shape.holes.reduce((s, h) => s + ringLength(h), 0);
  const area = Math.abs(ringArea(shape.outer)) - shape.holes.reduce((s, h) => s + Math.abs(ringArea(h)), 0);
  return per > 0 ? (2 * area) / per : 0;
}

// Principal axis angle of a ring (radians).
export function principalAngle(ring) {
  let mx = 0, my = 0;
  for (const p of ring) { mx += p.x; my += p.y; }
  mx /= ring.length; my /= ring.length;
  let sxx = 0, syy = 0, sxy = 0;
  for (const p of ring) { const dx = p.x - mx, dy = p.y - my; sxx += dx * dx; syy += dy * dy; sxy += dx * dy; }
  return 0.5 * Math.atan2(2 * sxy, sxx - syy);
}

/**
 * Generate all stitches for one object (fill / satin / run) of a shape.
 * Returns point list; the first point carries j:true when the caller must jump to it.
 */
export function stitchShape(shape, opt, start) {
  const o = { ...DEFAULTS, ...opt };
  const width = o.width ?? shapeWidth(shape);
  let pts = [];
  let pos = start;
  const kind = o.kind || 'auto';
  if (kind === 'run' || (kind === 'auto' && width < 0.9)) {
    const ring = pos ? startRingNear(shape.outer, pos) : shape.outer;
    const line = o.bean !== false ? beanLine(ring, Math.min(o.runLength, 2), true) : runLine(ring, o.runLength, true);
    if (kind === 'run') {
      for (const hole of shape.holes) {
        const hl = o.bean !== false ? beanLine(startRingNear(hole, line[line.length - 1]), Math.min(o.runLength, 2), true) : runLine(startRingNear(hole, line[line.length - 1]), o.runLength, true);
        if (hl.length) { hl[0] = { ...hl[0], j: true, t: dist(line[line.length - 1], hl[0]) > o.trimDistance }; line.push(...hl); }
      }
    }
    return line;
  }
  const isSatin = kind === 'satin' || (kind === 'auto' && width <= o.satinMax * 0.8);
  let angle = o.angle;
  if (angle === undefined || angle === null) {
    angle = isSatin ? bestSatinAngle(shape, o) : Math.PI / 4;
  }
  if (isSatin && kind !== 'fill' && (o.angle === undefined || o.angle === null) && o.turning !== false) {
    const sat = satinShape(shape, o);
    if (sat) {
      // all centre-line underlays first, then every satin column on top
      if (sat.underlays.length) {
        const ul = joinRuns(sat.underlays, pos, shape, o);
        if (ul.length) {
          if (pts.length) { const d = dist(pts[pts.length - 1], ul[0]); if (d > 1.2 && !segmentInside(pts[pts.length - 1], ul[0], shape)) ul[0] = { ...ul[0], j: true, t: d > o.trimDistance }; }
          pts.push(...ul); pos = pts[pts.length - 1];
        }
      }
      // leftover spots (serif balls, junction corners): small satin patches sewn first, so the columns cover their edges,
      // laid in the direction that crosses them best so they blend into the stroke
      const patches = [];
      for (const g of sat.gaps) {
        // a blob gets its own small turning-satin column; fall back to straight satin
        const sub = g.width && g.width > 0.6 ? satinShape(g, { ...o, underlay: false, pullComp: 0.05 }) : null;
        if (sub && sub.runs.length) patches.push(...sub.runs);
        else patches.push(...fillShape(g, { ...o, mode: 'satin', angle: bestSatinAngle(g, o), spacing: o.satinSpacing, pullComp: 0.1 }));
      }
      if (patches.length) {
        const gj = joinRuns(patches, pos, null, o);
        if (gj.length) {
          if (pos && pts.length) {
            const d = dist(pos, gj[0]);
            if (d > 1.2) {
              if (segmentInside(pos, gj[0], shape)) pts.push(...runLine([pos, gj[0]], o.runLength).slice(1, -1));
              else gj[0] = { ...gj[0], j: true, t: d > o.trimDistance };
            }
          }
          pts.push(...gj); pos = pts[pts.length - 1];
        }
      }
      const top = joinRuns(sat.runs, pos, shape, o);
      if (pts.length && top.length) {
        const d = dist(pts[pts.length - 1], top[0]);
        if (d > 1.2) {
          if (segmentInside(pts[pts.length - 1], top[0], shape)) pts.push(...runLine([pts[pts.length - 1], top[0]], o.runLength).slice(1, -1));
          else top[0] = { ...top[0], j: true, t: d > o.trimDistance };
        }
      }
      pts.push(...top);
      return pts;
    }
  }
  if (o.underlay && width > 1.4) {
    // edge-walk underlay just inside the outline
    const inset = Math.min(0.5, width / 5);
    const ringIn = offsetRing(shape.outer, -inset);
    const r0 = pos ? startRingNear(ringIn, pos) : ringIn;
    const edge = runLine(r0, 2.5, true);
    if (edge.length > 2) { pts.push(...edge); pos = pts[pts.length - 1]; }
    if (!isSatin && width > 3) {
      // sparse perpendicular fill underlay
      const ul = fillShape({ outer: ringIn, holes: shape.holes.map((h) => offsetRing(h, inset)) }, {
        ...o, mode: 'tatami', angle: angle + Math.PI / 2, spacing: 2.2, stitchLength: 3.5, pullComp: 0,
      });
      const joined = joinRuns(ul, pos, shape, o);
      if (joined.length) { pts.push(...joined); pos = pts[pts.length - 1]; }
    }
  }
  const runs = fillShape(shape, { ...o, angle, mode: isSatin ? 'auto' : 'tatami' });
  const top = joinRuns(runs, pos, shape, o);
  if (pts.length && top.length) {
    const d = dist(pts[pts.length - 1], top[0]);
    if (d > 1.2 && !segmentInside(pts[pts.length - 1], top[0], shape)) top[0] = { ...top[0], j: true, t: d > o.trimDistance };
    else if (d > 1.2) {
      const tr = runLine([pts[pts.length - 1], top[0]], o.runLength);
      pts.push(...tr.slice(1, -1));
    }
  }
  pts.push(...top);
  return pts;
}

// Split long stitches so no single movement exceeds maxLen (mm); jumps stay jumps.
export function limitStitchLength(pts, maxLen = 7) {
  const out = [];
  for (let i = 0; i < pts.length; i++) {
    const p = pts[i];
    const q = out[out.length - 1];
    if (q && !p.j) {
      const d = dist(p, q);
      if (d > maxLen) {
        const n = Math.ceil(d / maxLen);
        for (let k = 1; k < n; k++) out.push({ x: q.x + ((p.x - q.x) * k) / n, y: q.y + ((p.y - q.y) * k) / n });
      }
    }
    out.push(p);
  }
  return out;
}

// Remove stitches shorter than min (keeps jump/trim markers).
export function dropTiny(pts, min) {
  const out = [];
  for (const p of pts) {
    const q = out[out.length - 1];
    if (q && !p.j && dist(p, q) < min) continue;
    out.push(p);
  }
  return out;
}

// Lock (tie) stitches – a few tiny back-and-forth stitches.
export function lockAt(p, toward, size = 0.6) {
  let dx = 1, dy = 0;
  if (toward) {
    const L = dist(p, toward);
    if (L > 1e-6) { dx = (toward.x - p.x) / L; dy = (toward.y - p.y) / L; }
  }
  return [
    { x: p.x + dx * size, y: p.y + dy * size },
    { x: p.x, y: p.y },
    { x: p.x + dx * size, y: p.y + dy * size },
    { x: p.x, y: p.y },
  ];
}

// Pick the row angle that crosses the strokes of a narrow shape (short rows).
export function bestSatinAngle(shape, o = DEFAULTS) {
  const rings = [shape.outer, ...shape.holes];
  let best = principalAngle(shape.outer) + Math.PI / 2, bestScore = Infinity;
  const sp = Math.max(0.3, (o.satinSpacing || 0.4) * 2);
  for (let k = 0; k < 12; k++) {
    const a = (k * Math.PI) / 12;
    const rot = rings.map((r) => r.map((p) => rotatePoint(p, -a)));
    const rows = scanRows(rot, sp);
    let score = 0, n = 0;
    for (const r of rows) for (const sg of r.segs) {
      const L = sg[1] - sg[0];
      score += L * L + (L > o.satinMax ? 4 * L * L : 0);
      n++;
    }
    score = n ? score / n + 0.02 * n : Infinity;
    if (score < bestScore) { bestScore = score; best = a; }
  }
  return best;
}
