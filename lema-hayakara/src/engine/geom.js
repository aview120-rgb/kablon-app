// Geometry helpers. All coordinates are in millimetres unless noted.
// A "ring" is an array of {x,y} points (implicitly closed).
// A "shape" is {outer: ring, holes: ring[]}.

export function ringArea(r) {
  let a = 0;
  for (let i = 0, n = r.length; i < n; i++) {
    const p = r[i], q = r[(i + 1) % n];
    a += p.x * q.y - q.x * p.y;
  }
  return a / 2;
}

export function shapeArea(s) {
  let a = Math.abs(ringArea(s.outer));
  for (const h of s.holes) a -= Math.abs(ringArea(h));
  return a;
}

export function pointInRing(x, y, r) {
  let inside = false;
  for (let i = 0, j = r.length - 1; i < r.length; j = i++) {
    const a = r[i], b = r[j];
    if ((a.y > y) !== (b.y > y) && x < ((b.x - a.x) * (y - a.y)) / (b.y - a.y) + a.x) inside = !inside;
  }
  return inside;
}

export function pointInShape(x, y, s) {
  if (!pointInRing(x, y, s.outer)) return false;
  for (const h of s.holes) if (pointInRing(x, y, h)) return false;
  return true;
}

export function ringBounds(r) {
  let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity;
  for (const p of r) {
    if (p.x < minX) minX = p.x;
    if (p.y < minY) minY = p.y;
    if (p.x > maxX) maxX = p.x;
    if (p.y > maxY) maxY = p.y;
  }
  return { minX, minY, maxX, maxY };
}

export function shapesBounds(shapes) {
  let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity;
  for (const s of shapes) {
    const b = ringBounds(s.outer);
    minX = Math.min(minX, b.minX); minY = Math.min(minY, b.minY);
    maxX = Math.max(maxX, b.maxX); maxY = Math.max(maxY, b.maxY);
  }
  return { minX, minY, maxX, maxY };
}

function perpDist(p, a, b) {
  const dx = b.x - a.x, dy = b.y - a.y;
  const l2 = dx * dx + dy * dy;
  if (l2 === 0) return Math.hypot(p.x - a.x, p.y - a.y);
  let t = ((p.x - a.x) * dx + (p.y - a.y) * dy) / l2;
  t = Math.max(0, Math.min(1, t));
  return Math.hypot(p.x - (a.x + t * dx), p.y - (a.y + t * dy));
}

// Douglas–Peucker on an open polyline (iterative to avoid deep recursion).
export function simplifyLine(pts, tol) {
  if (pts.length < 3) return pts.slice();
  const keep = new Uint8Array(pts.length);
  keep[0] = keep[pts.length - 1] = 1;
  const stack = [[0, pts.length - 1]];
  while (stack.length) {
    const [s, e] = stack.pop();
    let maxD = 0, idx = -1;
    for (let i = s + 1; i < e; i++) {
      const d = perpDist(pts[i], pts[s], pts[e]);
      if (d > maxD) { maxD = d; idx = i; }
    }
    if (maxD > tol && idx > 0) {
      keep[idx] = 1;
      stack.push([s, idx], [idx, e]);
    }
  }
  return pts.filter((_, i) => keep[i]);
}

// Simplify a closed ring: split at the two farthest-apart points.
export function simplifyRing(r, tol) {
  if (r.length < 5) return r.slice();
  let far = 0, fd = -1;
  for (let i = 1; i < r.length; i++) {
    const d = (r[i].x - r[0].x) ** 2 + (r[i].y - r[0].y) ** 2;
    if (d > fd) { fd = d; far = i; }
  }
  const a = simplifyLine(r.slice(0, far + 1), tol);
  const b = simplifyLine(r.slice(far).concat([r[0]]), tol);
  const out = a.concat(b.slice(1, -1));
  return out.length >= 3 ? out : r.slice();
}

// Chaikin corner cutting – smooths pixel staircase outlines.
export function chaikin(r, iterations = 1) {
  let cur = r;
  for (let k = 0; k < iterations; k++) {
    const n = cur.length;
    if (n < 3) return cur;
    const out = [];
    for (let i = 0; i < n; i++) {
      const p = cur[i], q = cur[(i + 1) % n];
      out.push({ x: 0.75 * p.x + 0.25 * q.x, y: 0.75 * p.y + 0.25 * q.y });
      out.push({ x: 0.25 * p.x + 0.75 * q.x, y: 0.25 * p.y + 0.75 * q.y });
    }
    cur = out;
  }
  return cur;
}

export function transformRing(r, m) {
  // m = [a,b,c,d,e,f]  x' = a*x + c*y + e ; y' = b*x + d*y + f
  return r.map((p) => ({ x: m[0] * p.x + m[2] * p.y + m[4], y: m[1] * p.x + m[3] * p.y + m[5] }));
}

export function transformShape(s, m) {
  return { outer: transformRing(s.outer, m), holes: s.holes.map((h) => transformRing(h, m)) };
}

export function rotatePoint(p, ang) {
  const c = Math.cos(ang), s = Math.sin(ang);
  return { x: p.x * c - p.y * s, y: p.x * s + p.y * c };
}

// Group a soup of rings into shapes (outer + holes) by nesting depth.
export function ringsToShapes(rings) {
  const items = rings
    .filter((r) => r.length >= 3)
    .map((r) => ({ r, a: Math.abs(ringArea(r)), b: ringBounds(r) }))
    .filter((it) => it.a > 1e-9)
    .sort((p, q) => q.a - p.a);
  const depth = new Array(items.length).fill(0);
  const parent = new Array(items.length).fill(-1);
  for (let i = 0; i < items.length; i++) {
    const p = items[i].r[0];
    // the smallest containing ring is the parent
    for (let j = i - 1; j >= 0; j--) {
      const bj = items[j].b;
      if (p.x < bj.minX || p.x > bj.maxX || p.y < bj.minY || p.y > bj.maxY) continue;
      if (pointInRing(p.x, p.y, items[j].r)) { parent[i] = j; depth[i] = depth[j] + 1; break; }
    }
  }
  const shapes = [];
  const shapeOf = new Map();
  for (let i = 0; i < items.length; i++) {
    if (depth[i] % 2 === 0) {
      const s = { outer: items[i].r, holes: [] };
      shapeOf.set(i, s);
      shapes.push(s);
    } else if (parent[i] >= 0 && shapeOf.has(parent[i])) {
      shapeOf.get(parent[i]).holes.push(items[i].r);
    }
  }
  return shapes;
}

// ---- SVG path parsing (used for text glyphs and SVG import) ----
export function parseSvgPath(d, curveTol = 0.05) {
  const tokens = d.match(/[a-zA-Z]|-?(?:\d*\.\d+|\d+\.?)(?:[eE][-+]?\d+)?/g) || [];
  const rings = [];
  let cur = [], cx = 0, cy = 0, sx = 0, sy = 0, cmd = '', i = 0;
  let lcx = 0, lcy = 0; // last control point
  const num = () => parseFloat(tokens[i++]);
  const isNum = () => i < tokens.length && !/^[a-zA-Z]$/.test(tokens[i]);
  const flush = () => { if (cur.length > 2) rings.push(cur); cur = []; };
  const cubic = (x1, y1, x2, y2, x, y) => {
    const len = Math.hypot(x1 - cx, y1 - cy) + Math.hypot(x2 - x1, y2 - y1) + Math.hypot(x - x2, y - y2);
    const n = Math.max(2, Math.min(64, Math.ceil(Math.sqrt(len / curveTol))));
    for (let k = 1; k <= n; k++) {
      const t = k / n, u = 1 - t;
      cur.push({
        x: u * u * u * cx + 3 * u * u * t * x1 + 3 * u * t * t * x2 + t * t * t * x,
        y: u * u * u * cy + 3 * u * u * t * y1 + 3 * u * t * t * y2 + t * t * t * y,
      });
    }
    lcx = x2; lcy = y2; cx = x; cy = y;
  };
  const quad = (x1, y1, x, y) => {
    const len = Math.hypot(x1 - cx, y1 - cy) + Math.hypot(x - x1, y - y1);
    const n = Math.max(2, Math.min(64, Math.ceil(Math.sqrt(len / curveTol))));
    for (let k = 1; k <= n; k++) {
      const t = k / n, u = 1 - t;
      cur.push({ x: u * u * cx + 2 * u * t * x1 + t * t * x, y: u * u * cy + 2 * u * t * y1 + t * t * y });
    }
    lcx = x1; lcy = y1; cx = x; cy = y;
  };
  while (i < tokens.length) {
    if (!isNum()) cmd = tokens[i++];
    const rel = cmd === cmd.toLowerCase();
    const C = cmd.toUpperCase();
    const ox = rel ? cx : 0, oy = rel ? cy : 0;
    switch (C) {
      case 'M': {
        flush();
        cx = num() + ox; cy = num() + oy; sx = cx; sy = cy;
        cur.push({ x: cx, y: cy });
        cmd = rel ? 'l' : 'L';
        lcx = cx; lcy = cy;
        break;
      }
      case 'L': cx = num() + ox; cy = num() + oy; cur.push({ x: cx, y: cy }); lcx = cx; lcy = cy; break;
      case 'H': cx = num() + ox; cur.push({ x: cx, y: cy }); lcx = cx; lcy = cy; break;
      case 'V': cy = num() + oy; cur.push({ x: cx, y: cy }); lcx = cx; lcy = cy; break;
      case 'C': { const a = num() + ox, b = num() + oy, c = num() + ox, e = num() + oy, x = num() + ox, y = num() + oy; cubic(a, b, c, e, x, y); break; }
      case 'S': { const a = 2 * cx - lcx, b = 2 * cy - lcy; const c = num() + ox, e = num() + oy, x = num() + ox, y = num() + oy; cubic(a, b, c, e, x, y); break; }
      case 'Q': { const a = num() + ox, b = num() + oy, x = num() + ox, y = num() + oy; quad(a, b, x, y); break; }
      case 'T': { const a = 2 * cx - lcx, b = 2 * cy - lcy; const x = num() + ox, y = num() + oy; quad(a, b, x, y); break; }
      case 'A': {
        // approximate arcs with straight segments via endpoint param conversion
        const rx = Math.abs(num()), ry = Math.abs(num()), rot = num(), large = num(), sweep = num();
        const x = num() + ox, y = num() + oy;
        arcTo(cur, cx, cy, rx, ry, rot, large, sweep, x, y);
        cx = x; cy = y; lcx = cx; lcy = cy;
        break;
      }
      case 'Z': cx = sx; cy = sy; flush(); lcx = cx; lcy = cy; break;
      default: i++; // unknown token, skip
    }
  }
  flush();
  // remove closing duplicates
  return rings.map((r) => {
    const out = [];
    for (const p of r) {
      const l = out[out.length - 1];
      if (!l || Math.abs(l.x - p.x) > 1e-9 || Math.abs(l.y - p.y) > 1e-9) out.push(p);
    }
    if (out.length > 1) {
      const f = out[0], l = out[out.length - 1];
      if (Math.abs(f.x - l.x) < 1e-9 && Math.abs(f.y - l.y) < 1e-9) out.pop();
    }
    return out;
  });
}

function arcTo(out, x1, y1, rx, ry, phiDeg, fa, fs, x2, y2) {
  if (rx === 0 || ry === 0) { out.push({ x: x2, y: y2 }); return; }
  const phi = (phiDeg * Math.PI) / 180, cp = Math.cos(phi), sp = Math.sin(phi);
  const dx = (x1 - x2) / 2, dy = (y1 - y2) / 2;
  const x1p = cp * dx + sp * dy, y1p = -sp * dx + cp * dy;
  let lam = (x1p * x1p) / (rx * rx) + (y1p * y1p) / (ry * ry);
  if (lam > 1) { rx *= Math.sqrt(lam); ry *= Math.sqrt(lam); }
  const sign = fa === fs ? -1 : 1;
  const num = rx * rx * ry * ry - rx * rx * y1p * y1p - ry * ry * x1p * x1p;
  const den = rx * rx * y1p * y1p + ry * ry * x1p * x1p;
  const co = sign * Math.sqrt(Math.max(0, num / den));
  const cxp = (co * rx * y1p) / ry, cyp = (-co * ry * x1p) / rx;
  const cx = cp * cxp - sp * cyp + (x1 + x2) / 2, cy = sp * cxp + cp * cyp + (y1 + y2) / 2;
  const ang = (ux, uy, vx, vy) => Math.atan2(ux * vy - uy * vx, ux * vx + uy * vy);
  const t1 = ang(1, 0, (x1p - cxp) / rx, (y1p - cyp) / ry);
  let dt = ang((x1p - cxp) / rx, (y1p - cyp) / ry, (-x1p - cxp) / rx, (-y1p - cyp) / ry);
  if (!fs && dt > 0) dt -= 2 * Math.PI;
  if (fs && dt < 0) dt += 2 * Math.PI;
  const n = Math.max(4, Math.ceil(Math.abs(dt) / (Math.PI / 16)));
  for (let k = 1; k <= n; k++) {
    const t = t1 + (dt * k) / n;
    const ex = rx * Math.cos(t), ey = ry * Math.sin(t);
    out.push({ x: cp * ex - sp * ey + cx, y: sp * ex + cp * ey + cy });
  }
}

// Offset a ring inward/outward approximately by moving vertices along averaged normals.
export function offsetRing(r, d) {
  const n = r.length;
  if (n < 3) return r.slice();
  const ccw = ringArea(r) > 0 ? 1 : -1;
  const out = [];
  for (let i = 0; i < n; i++) {
    const p0 = r[(i - 1 + n) % n], p = r[i], p1 = r[(i + 1) % n];
    let ax = p.x - p0.x, ay = p.y - p0.y, bx = p1.x - p.x, by = p1.y - p.y;
    const la = Math.hypot(ax, ay) || 1, lb = Math.hypot(bx, by) || 1;
    ax /= la; ay /= la; bx /= lb; by /= lb;
    // outward normal for CCW ring (y up math) is (dy,-dx)
    let nx = (ay + by) * ccw, ny = -(ax + bx) * ccw;
    const ln = Math.hypot(nx, ny) || 1;
    nx /= ln; ny /= ln;
    const cosHalf = Math.max(0.35, (1 + ax * bx + ay * by) / 2 > 0 ? Math.sqrt((1 + ax * bx + ay * by) / 2) : 0.35);
    out.push({ x: p.x + (nx * d) / cosHalf, y: p.y + (ny * d) / cosHalf });
  }
  return out;
}

export function ringLength(r, closed = true) {
  let L = 0;
  for (let i = 0; i < r.length - (closed ? 0 : 1); i++) {
    const p = r[i], q = r[(i + 1) % r.length];
    L += Math.hypot(q.x - p.x, q.y - p.y);
  }
  return L;
}

export function dist(a, b) { return Math.hypot(a.x - b.x, a.y - b.y); }

// Densify a closed ring so consecutive points are at most `step` apart.
export function densifyRing(r, step) {
  const out = [];
  for (let i = 0; i < r.length; i++) {
    const a = r[i], b = r[(i + 1) % r.length];
    const L = Math.hypot(b.x - a.x, b.y - a.y);
    const n = Math.max(1, Math.ceil(L / step));
    for (let k = 0; k < n; k++) out.push({ x: a.x + ((b.x - a.x) * k) / n, y: a.y + ((b.y - a.y) * k) / n });
  }
  return out;
}

// Moving-average smoothing of a dense closed ring; keeps strong corners sharp.
export function smoothRing(r, radius = 2, cornerCos = 0.2) {
  const n = r.length;
  if (n < 8) return r.slice();
  // detect corners on the pixel outline: direction change over a window
  const corner = new Uint8Array(n);
  const W = Math.max(3, radius * 2);
  for (let i = 0; i < n; i++) {
    const a = r[(i - W + n) % n], p = r[i], b = r[(i + W) % n];
    const ux = p.x - a.x, uy = p.y - a.y, vx = b.x - p.x, vy = b.y - p.y;
    const lu = Math.hypot(ux, uy) || 1, lv = Math.hypot(vx, vy) || 1;
    if ((ux * vx + uy * vy) / (lu * lv) < cornerCos) corner[i] = 1;
  }
  const out = [];
  for (let i = 0; i < n; i++) {
    if (corner[i]) { out.push({ ...r[i] }); continue; }
    let sx = 0, sy = 0, c = 0;
    for (let k = -radius; k <= radius; k++) { const q = r[(i + k + n) % n]; sx += q.x; sy += q.y; c++; }
    out.push({ x: sx / c, y: sy / c });
  }
  return out;
}
