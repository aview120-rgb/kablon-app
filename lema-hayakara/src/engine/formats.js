// Machine file formats. Pattern = { stitches: [[x, y, cmd]], colors: ['#rrggbb'], name }
// Units: 0.1 mm, y axis pointing down (screen). cmd: STITCH/JUMP/TRIM/COLOR/END.
export const STITCH = 0, JUMP = 1, TRIM = 2, COLOR = 3, END = 4;

class ByteWriter {
  constructor() { this.buf = new Uint8Array(1 << 16); this.n = 0; }
  ensure(k) {
    if (this.n + k <= this.buf.length) return;
    let L = this.buf.length * 2; while (L < this.n + k) L *= 2;
    const b = new Uint8Array(L); b.set(this.buf.subarray(0, this.n)); this.buf = b;
  }
  u8(v) { this.ensure(1); this.buf[this.n++] = v & 0xff; }
  i8(v) { this.u8(v < 0 ? v + 256 : v); }
  u16(v) { this.u8(v); this.u8(v >> 8); }
  u16be(v) { this.u8(v >> 8); this.u8(v); }
  i16(v) { this.u16(v < 0 ? v + 65536 : v); }
  u32(v) { this.u16(v & 0xffff); this.u16((v >>> 16) & 0xffff); }
  i32(v) { this.u32(v >>> 0); }
  f32(v) { const b = new DataView(new ArrayBuffer(4)); b.setFloat32(0, v, true); for (let i = 0; i < 4; i++) this.u8(b.getUint8(i)); }
  bytes(arr) { this.ensure(arr.length); this.buf.set(arr, this.n); this.n += arr.length; }
  str(s) { for (let i = 0; i < s.length; i++) this.u8(s.charCodeAt(i) & 0xff); }
  at(pos, fn) { const save = this.n; this.n = pos; fn(); this.n = save; }
  result() { return this.buf.slice(0, this.n); }
}

// Re-express a pattern with integer coordinates and moves no longer than maxStep.
export function normalize(pattern, maxStep = 121) {
  const out = [];
  let cx = 0, cy = 0;
  for (const [x0, y0, c] of pattern.stitches) {
    const x = Math.round(x0), y = Math.round(y0);
    if (c === STITCH || c === JUMP) {
      const dx = x - cx, dy = y - cy;
      const n = Math.max(1, Math.ceil(Math.max(Math.abs(dx), Math.abs(dy)) / maxStep));
      let px = cx, py = cy;
      for (let k = 1; k <= n; k++) {
        const nx = cx + Math.round((dx * k) / n), ny = cy + Math.round((dy * k) / n);
        // intermediate parts of a long stitch become jumps on most machines anyway
        out.push([nx, ny, k < n && c === STITCH && n > 1 ? JUMP : c]);
        px = nx; py = ny;
      }
      cx = px; cy = py;
    } else {
      out.push([cx, cy, c]);
    }
  }
  return out;
}

export function bounds(stitches) {
  let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity;
  for (const [x, y, c] of stitches) {
    if (c !== STITCH) continue;
    if (x < minX) minX = x; if (x > maxX) maxX = x;
    if (y < minY) minY = y; if (y > maxY) maxY = y;
  }
  if (minX === Infinity) return [0, 0, 0, 0];
  return [minX, minY, maxX, maxY];
}

// ---------------- DST (Tajima) ----------------
function dstRecord(dx, dy, type) {
  dy = -dy;
  let b0 = 0, b1 = 0, b2 = 0;
  if (type === COLOR) return [0, 0, 0xc3];
  if (type === END) return [0, 0, 0xf3];
  if (type === JUMP) b2 |= 0x80;
  b2 |= 0x03;
  let x = dx, y = dy;
  if (x > 40) { b2 |= 1 << 2; x -= 81; }
  if (x < -40) { b2 |= 1 << 3; x += 81; }
  if (x > 13) { b1 |= 1 << 2; x -= 27; }
  if (x < -13) { b1 |= 1 << 3; x += 27; }
  if (x > 4) { b0 |= 1 << 2; x -= 9; }
  if (x < -4) { b0 |= 1 << 3; x += 9; }
  if (x > 1) { b1 |= 1 << 0; x -= 3; }
  if (x < -1) { b1 |= 1 << 1; x += 3; }
  if (x > 0) { b0 |= 1 << 0; x -= 1; }
  if (x < 0) { b0 |= 1 << 1; x += 1; }
  if (y > 40) { b2 |= 1 << 5; y -= 81; }
  if (y < -40) { b2 |= 1 << 4; y += 81; }
  if (y > 13) { b1 |= 1 << 5; y -= 27; }
  if (y < -13) { b1 |= 1 << 4; y += 27; }
  if (y > 4) { b0 |= 1 << 5; y -= 9; }
  if (y < -4) { b0 |= 1 << 4; y += 9; }
  if (y > 1) { b1 |= 1 << 7; y -= 3; }
  if (y < -1) { b1 |= 1 << 6; y += 3; }
  if (y > 0) { b0 |= 1 << 7; y -= 1; }
  if (y < 0) { b0 |= 1 << 6; y += 1; }
  if (x !== 0 || y !== 0) throw new Error('DST step too large');
  return [b0, b1, b2];
}

export function writeDST(pattern, { trimJumps = 3 } = {}) {
  const st = normalize(pattern, 121);
  const w = new ByteWriter();
  const b = bounds(st);
  const count = st.filter((s) => s[2] === STITCH || s[2] === JUMP).length + 1;
  const colorChanges = st.filter((s) => s[2] === COLOR).length;
  const name = (pattern.name || 'design').replace(/[^\x20-\x7e]/g, '').slice(0, 16) || 'design';
  const pad = (v, n) => String(v).padStart(n, ' ');
  const last = st.length ? st[st.length - 1] : [0, 0];
  const sign = (v) => (v >= 0 ? '+' : '-');
  w.str(`LA:${name.padEnd(16, ' ')}\r`);
  w.str(`ST:${pad(count, 7)}\r`);
  w.str(`CO:${pad(colorChanges, 3)}\r`);
  w.str(`+X:${pad(Math.abs(b[2]), 5)}\r`);
  w.str(`-X:${pad(Math.abs(b[0]), 5)}\r`);
  w.str(`+Y:${pad(Math.abs(b[3]), 5)}\r`);
  w.str(`-Y:${pad(Math.abs(b[1]), 5)}\r`);
  w.str(`AX:${sign(last[0])}${pad(Math.abs(last[0]), 5)}\r`);
  w.str(`AY:${sign(-last[1])}${pad(Math.abs(last[1]), 5)}\r`);
  w.str(`MX:+${pad(0, 5)}\r`);
  w.str(`MY:+${pad(0, 5)}\r`);
  w.str(`PD:******\r`);
  w.u8(0x1a);
  while (w.n < 512) w.u8(0x20);
  let cx = 0, cy = 0;
  for (const [x, y, c] of st) {
    if (c === TRIM) {
      // DST has no trim command: machines (SWF, Tajima…) trim after N consecutive jumps.
      let d = -4;
      w.bytes(dstRecord(2, 2, JUMP));
      for (let p = 1; p < trimJumps - 1; p++) { w.bytes(dstRecord(d, d, JUMP)); d = -d; }
      w.bytes(dstRecord(d / 2, d / 2, JUMP));
      continue;
    }
    if (c === COLOR) { w.bytes(dstRecord(0, 0, COLOR)); continue; }
    if (c === END) continue;
    w.bytes(dstRecord(x - cx, y - cy, c));
    cx = x; cy = y;
  }
  w.bytes(dstRecord(0, 0, END));
  return w.result();
}

export function readDST(bytes) {
  const u = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes);
  const header = String.fromCharCode(...u.slice(0, 512));
  const name = (header.match(/LA:([^\r]*)/) || [])[1]?.trim() || '';
  const st = [];
  let x = 0, y = 0;
  let jumpsInRow = 0;
  const bit = (v, n) => (v >> n) & 1;
  for (let i = 512; i + 2 < u.length; i += 3) {
    const b0 = u[i], b1 = u[i + 1], b2 = u[i + 2];
    if ((b2 & 0xf3) === 0xf3 && !(b0 | b1)) { break; }
    let dx = 0, dy = 0;
    dx += bit(b2, 2) * 81 - bit(b2, 3) * 81 + bit(b1, 2) * 27 - bit(b1, 3) * 27 + bit(b0, 2) * 9 - bit(b0, 3) * 9 + bit(b1, 0) * 3 - bit(b1, 1) * 3 + bit(b0, 0) - bit(b0, 1);
    dy += bit(b2, 5) * 81 - bit(b2, 4) * 81 + bit(b1, 5) * 27 - bit(b1, 4) * 27 + bit(b0, 5) * 9 - bit(b0, 4) * 9 + bit(b1, 7) * 3 - bit(b1, 6) * 3 + bit(b0, 7) - bit(b0, 6);
    dy = -dy;
    if ((b2 & 0xc3) === 0xc3) {
      st.push([x + dx, y + dy, COLOR]);
      x += dx; y += dy;
      jumpsInRow = 0;
      continue;
    }
    x += dx; y += dy;
    if (b2 & 0x80) {
      jumpsInRow++;
      st.push([x, y, JUMP]);
    } else {
      if (jumpsInRow >= 2) {
        // series of jumps = trim on most machines; mark it
        const at = st.length - jumpsInRow;
        st.splice(at, 0, [st[at - 1]?.[0] ?? 0, st[at - 1]?.[1] ?? 0, TRIM]);
      }
      jumpsInRow = 0;
      st.push([x, y, STITCH]);
    }
  }
  st.push([x, y, END]);
  return { name, stitches: st, colors: [] };
}

// ---------------- EXP (Melco) ----------------
export function writeEXP(pattern) {
  const st = normalize(pattern, 127);
  const w = new ByteWriter();
  let cx = 0, cy = 0;
  for (const [x, y, c] of st) {
    const dx = x - cx, dy = y - cy;
    if (c === STITCH) { w.i8(dx); w.i8(-dy); }
    else if (c === JUMP) { w.u8(0x80); w.u8(0x04); w.i8(dx); w.i8(-dy); }
    else if (c === TRIM) { w.u8(0x80); w.u8(0x80); w.u8(0x07); w.u8(0x00); continue; }
    else if (c === COLOR) { w.u8(0x80); w.u8(0x01); w.u8(0); w.u8(0); continue; }
    else continue;
    cx = x; cy = y;
  }
  return w.result();
}

export function readEXP(bytes) {
  const u = new Uint8Array(bytes);
  const st = [];
  let x = 0, y = 0;
  const s8 = (v) => (v > 127 ? v - 256 : v);
  for (let i = 0; i + 1 < u.length;) {
    if (u[i] === 0x80) {
      const c = u[i + 1];
      if (c === 0x01) { st.push([x, y, COLOR]); i += 4; continue; }
      if (c === 0x80) { st.push([x, y, TRIM]); i += 4; continue; }
      if (c === 0x04 || c === 0x02) { x += s8(u[i + 2]); y -= s8(u[i + 3]); st.push([x, y, JUMP]); i += 4; continue; }
      i += 2; continue;
    }
    x += s8(u[i]); y -= s8(u[i + 1]); st.push([x, y, STITCH]); i += 2;
  }
  st.push([x, y, END]);
  return { name: '', stitches: st, colors: [] };
}

// ---------------- JEF (Janome) ----------------
import { JEF_THREADS, PEC_THREADS, PEC_BLANK } from './threads.js';

function nearestIndex(hex, list, skip0 = true) {
  const h = hex.replace('#', '');
  const r = parseInt(h.slice(0, 2), 16), g = parseInt(h.slice(2, 4), 16), b = parseInt(h.slice(4, 6), 16);
  let best = skip0 ? 1 : 0, bd = Infinity;
  for (let i = skip0 ? 1 : 0; i < list.length; i++) {
    const c = list[i][0];
    const R = (c >> 16) & 255, G = (c >> 8) & 255, B = c & 255;
    const rm = (r + R) / 2;
    const d = (2 + rm / 256) * (r - R) ** 2 + 4 * (g - G) ** 2 + (2 + (255 - rm) / 256) * (b - B) ** 2;
    if (d < bd) { bd = d; best = i; }
  }
  return best;
}

export function writeJEF(pattern, { date } = {}) {
  const st = normalize(pattern, 127);
  const w = new ByteWriter();
  const colorCount = Math.max(1, st.filter((s) => s[2] === COLOR).length + 1);
  const colors = [];
  for (let i = 0; i < colorCount; i++) colors.push(pattern.colors[i] || '#000000');
  const palette = colors.map((c) => nearestIndex(c, JEF_THREADS));
  let points = 1;
  for (const s of st) {
    if (s[2] === STITCH) points += 1;
    else if (s[2] === JUMP || s[2] === COLOR) points += 2;
  }
  const b = bounds(st);
  const dw = b[2] - b[0], dh = b[3] - b[1];
  const hw = Math.round(dw / 2), hh = Math.round(dh / 2);
  let hoop = 0;
  if (dw < 500 && dh < 500) hoop = 1; else if (dw < 1260 && dh < 1100) hoop = 3; else if (dw < 1400 && dh < 2000) hoop = 2; else if (dw < 2000 && dh < 2000) hoop = 4;
  const d = date || new Date().toISOString().replace(/[-:T]/g, '').slice(0, 14);
  w.u32(0x74 + colorCount * 8);
  w.u32(0x14);
  w.str(d.padEnd(14, '0').slice(0, 14));
  w.u8(0); w.u8(0);
  w.u32(colorCount);
  w.u32(points);
  w.u32(hoop);
  w.i32(hw); w.i32(hh); w.i32(hw); w.i32(hh);
  const edge = (ex, ey) => {
    if (Math.min(ex, ey) >= 0) { w.i32(ex); w.i32(ey); w.i32(ex); w.i32(ey); }
    else { w.i32(-1); w.i32(-1); w.i32(-1); w.i32(-1); }
  };
  edge(550 - hw, 550 - hh); edge(250 - hw, 250 - hh); edge(700 - hw, 1000 - hh); edge(700 - hw, 1000 - hh);
  for (const p of palette) w.u32(p);
  for (let i = 0; i < colorCount; i++) w.u32(0x0d);
  let cx = 0, cy = 0;
  for (const [x, y, c] of st) {
    const dx = x - cx, dy = y - cy;
    if (c === STITCH) { w.i8(dx); w.i8(-dy); }
    else if (c === COLOR) { w.u8(0x80); w.u8(0x01); w.i8(dx); w.i8(-dy); }
    else if (c === JUMP) { w.u8(0x80); w.u8(0x02); w.i8(dx); w.i8(-dy); }
    else continue;
    cx = x; cy = y;
  }
  w.u8(0x80); w.u8(0x10);
  return w.result();
}

// ---------------- PES (Brother, truncated v1 + PEC block) ----------------
function redMean(r1, g1, b1, r2, g2, b2) {
  const rm = Math.round((r1 + r2) / 2), r = r1 - r2, g = g1 - g2, b = b1 - b2;
  return (((512 + rm) * r * r) >> 8) + 4 * g * g + (((767 - rm) * b * b) >> 8);
}
function nearestIn(color, list) {
  const r = (color >> 16) & 255, g = (color >> 8) & 255, b = color & 255;
  let best = null, bd = Infinity;
  list.forEach((t, i) => {
    if (t === null) return;
    const d = redMean(r, g, b, (t >> 16) & 255, (t >> 8) & 255, t & 255);
    if (d <= bd) { bd = d; best = i; }
  });
  return best;
}
function hexInt(h) { return parseInt(String(h || '#000000').replace('#', ''), 16) || 0; }

function pecValue(w, v, long, flag) {
  if (!long && v > -64 && v < 63) { w.u8(v & 0x7f); return; }
  let x = v & 0x0fff;
  x |= 0x8000;
  x |= flag << 8;
  w.u8((x >> 8) & 0xff); w.u8(x & 0xff);
}

function pecMarkBit(g, x, y, stride = 6) {
  const i = y * stride + (x >> 3);
  if (x < 0 || y < 0 || x >= stride * 8 || i >= g.length) return;
  g[i] |= 1 << (x & 7);
}
function pecDrawScaled(ext, pts, g, stride, buffer) {
  const dw = ext[2] - ext[0] || 1, dh = ext[3] - ext[1] || 1;
  const gw = stride * 8, gh = g.length / stride;
  const scale = Math.min((gw - buffer) / dw, (gh - buffer) / dh);
  const tx = -((ext[2] + ext[0]) / 2) * scale + gw / 2;
  const ty = -((ext[3] + ext[1]) / 2) * scale + gh / 2;
  for (const p of pts) pecMarkBit(g, Math.floor(p[0] * scale + tx), Math.floor(p[1] * scale + ty), stride);
}

export function writePES(pattern) {
  const st = normalize(pattern, 2047);
  const w = new ByteWriter();
  w.str('#PES0001');
  w.bytes([0x16, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0]);
  // ---- PEC header ----
  const name = (pattern.name || 'design').replace(/[^\x20-\x7e]/g, '').slice(0, 8);
  w.str('LA:' + name.padEnd(16, ' ') + '\r');
  w.bytes([0x20, 0x20, 0x20, 0x20, 0x20, 0x20, 0x20, 0x20, 0x20, 0x20, 0x20, 0x20, 0xff, 0x00]);
  w.u8(6); w.u8(38);
  const nColors = st.filter((s) => s[2] === COLOR).length + 1;
  const threads = [];
  for (let i = 0; i < nColors; i++) threads.push(hexInt(pattern.colors?.[i]));
  // build unique palette like the reference implementation
  const chartSrc = PEC_THREADS.map((t, i) => (i === 0 ? null : t[0]));
  const chart = new Array(chartSrc.length).fill(null);
  for (const t of [...new Set(threads)]) {
    const idx = nearestIn(t, chartSrc);
    if (idx === null) break;
    chartSrc[idx] = null; chart[idx] = t;
  }
  const idxList = threads.map((t) => nearestIn(t, chart) ?? 1);
  w.bytes(new Array(12).fill(0x20));
  w.u8(idxList.length - 1);
  w.bytes(idxList);
  for (let i = idxList.length; i < 463; i++) w.u8(0x20);
  // ---- PEC stitch block ----
  const ext = bounds(st);
  const blockStart = w.n;
  w.u8(0); w.u8(0);
  w.u8(0); w.u8(0); w.u8(0);
  w.bytes([0x31, 0xff, 0xf0]);
  w.u16(Math.round(ext[2] - ext[0])); w.u16(Math.round(ext[3] - ext[1]));
  w.u16(0x1e0); w.u16(0x1b0);
  let cx = 0, cy = 0, colorTwo = true, jumping = true, init = true;
  for (const [x, y, c] of st) {
    const dx = x - cx, dy = y - cy;
    if (c === STITCH || c === JUMP) { cx = x; cy = y; }
    if (c === STITCH) {
      if (jumping) { if (dx !== 0 && dy !== 0) { pecValue(w, 0, false, 0); pecValue(w, 0, false, 0); } jumping = false; }
      pecValue(w, dx, false, 0); pecValue(w, dy, false, 0);
    } else if (c === JUMP) {
      jumping = true;
      const flag = init ? 0x10 : 0x20;
      pecValue(w, dx, true, flag); pecValue(w, dy, true, flag);
    } else if (c === COLOR) {
      if (jumping) { pecValue(w, 0, false, 0); pecValue(w, 0, false, 0); jumping = false; }
      w.bytes([0xfe, 0xb0, colorTwo ? 2 : 1]);
      colorTwo = !colorTwo;
    }
    if (c !== END) init = false;
  }
  w.u8(0xff);
  const blockLen = w.n - blockStart;
  w.at(blockStart + 2, () => { w.u8(blockLen & 0xff); w.u8((blockLen >> 8) & 0xff); w.u8((blockLen >> 16) & 0xff); });
  // ---- PEC graphics: overall + one per colour ----
  const all = PEC_BLANK.slice();
  pecDrawScaled(ext, st.filter((s) => s[2] === STITCH), all, 6, 4);
  w.bytes(all);
  let part = [];
  const parts = [];
  for (const s of st) { if (s[2] === COLOR) { parts.push(part); part = []; } else if (s[2] === STITCH) part.push(s); }
  parts.push(part);
  for (let i = 0; i < nColors; i++) {
    const g = PEC_BLANK.slice();
    pecDrawScaled(ext, parts[i] || [], g, 6, 5);
    w.bytes(g);
  }
  return w.result();
}

export const FORMATS = {
  dst: { label: 'DST – Tajima / SWF', write: writeDST, ext: 'dst' },
  exp: { label: 'EXP – Melco', write: writeEXP, ext: 'exp' },
  pes: { label: 'PES – Brother', write: writePES, ext: 'pes' },
  jef: { label: 'JEF – Janome', write: writeJEF, ext: 'jef' },
};

// ---------------- readers for Brother (PES/PEC) and Janome (JEF) ----------------
function hexOf(c) { return '#' + c.toString(16).padStart(6, '0'); }

export function readPEC(u, pos = 0) {
  const st = [];
  let x = 0, y = 0;
  const colorChanges = u[pos + 48];
  const count = colorChanges === 0xff ? 1 : colorChanges + 1;
  const colors = [];
  for (let i = 0; i < count; i++) colors.push(hexOf(PEC_THREADS[u[pos + 49 + i] % PEC_THREADS.length][0]));
  let p = pos + 528;
  const s12 = (v) => { v &= 0xfff; return v > 0x7ff ? v - 0x1000 : v; };
  const s7 = (v) => (v > 63 ? v - 128 : v);
  while (p + 1 < u.length) {
    let v1 = u[p++], v2 = u[p++];
    if (v1 === 0xff && v2 === 0x00) break;
    if (v1 === 0xfe && v2 === 0xb0) { p++; st.push([x, y, COLOR]); continue; }
    let jump = false, trim = false, dx, dy;
    if (v1 & 0x80) {
      if (v1 & 0x20) trim = true;
      if (v1 & 0x10) jump = true;
      dx = s12((v1 << 8) | v2);
      v2 = u[p++];
      if (v2 === undefined) break;
    } else dx = s7(v1);
    if (v2 & 0x80) {
      if (v2 & 0x20) trim = true;
      if (v2 & 0x10) jump = true;
      const v3 = u[p++];
      if (v3 === undefined) break;
      dy = s12((v2 << 8) | v3);
    } else dy = s7(v2);
    x += dx; y += dy;
    if (trim) { st.push([x - dx, y - dy, TRIM]); st.push([x, y, JUMP]); }
    else if (jump) st.push([x, y, JUMP]);
    else st.push([x, y, STITCH]);
  }
  st.push([x, y, END]);
  let name = '';
  for (let i = pos + 3; i < pos + 19; i++) name += String.fromCharCode(u[i]);
  return { name: name.trim(), stitches: st, colors };
}

export function readPES(bytes) {
  const u = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes);
  const sig = String.fromCharCode(...u.slice(0, 8));
  if (sig === '#PEC0001') return readPEC(u, 8);
  if (!sig.startsWith('#PES')) throw new Error('not a PES file');
  const pecPos = u[8] | (u[9] << 8) | (u[10] << 16) | (u[11] << 24);
  return readPEC(u, pecPos);
}

export function readJEF(bytes) {
  const u = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes);
  const dv = new DataView(u.buffer, u.byteOffset, u.byteLength);
  const stitchOffset = dv.getUint32(0, true);
  const count = dv.getUint32(24, true);
  const colors = [];
  for (let i = 0; i < count && 116 + i * 4 + 4 <= u.length; i++) {
    const idx = Math.abs(dv.getInt32(116 + i * 4, true));
    const t = JEF_THREADS[idx % JEF_THREADS.length];
    colors.push(hexOf(t ? t[0] : 0));
  }
  const s8 = (v) => (v > 127 ? v - 256 : v);
  const st = [];
  let x = 0, y = 0, p = stitchOffset;
  while (p + 1 < u.length) {
    const b0 = u[p], b1 = u[p + 1];
    p += 2;
    if (b0 !== 0x80) { x += s8(b0); y -= s8(b1); st.push([x, y, STITCH]); continue; }
    const ctrl = b1;
    if (p + 1 >= u.length) break;
    const dx = s8(u[p]), dy = -s8(u[p + 1]);
    p += 2;
    if (ctrl === 0x02) { x += dx; y += dy; st.push([x, y, JUMP]); continue; }
    if (ctrl === 0x01) { st.push([x, y, COLOR]); continue; }
    break;
  }
  st.push([x, y, END]);
  return { name: '', stitches: st, colors };
}
