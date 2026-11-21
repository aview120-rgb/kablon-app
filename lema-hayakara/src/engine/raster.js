// Raster processing: colour quantisation, region clean-up and outline tracing.
// Works on plain {width, height, data: Uint8ClampedArray RGBA} objects so it
// runs both in the browser and in Node tests.

export function makeImage(width, height) {
  return { width, height, data: new Uint8ClampedArray(width * height * 4) };
}

// Bilinear-ish (box) resize so that the longest side is <= maxSide.
export function resizeImage(img, w, h) {
  const out = makeImage(w, h);
  const sx = img.width / w, sy = img.height / h;
  const s = img.data, d = out.data;
  for (let y = 0; y < h; y++) {
    const y0 = Math.floor(y * sy), y1 = Math.max(y0 + 1, Math.floor((y + 1) * sy));
    for (let x = 0; x < w; x++) {
      const x0 = Math.floor(x * sx), x1 = Math.max(x0 + 1, Math.floor((x + 1) * sx));
      let r = 0, g = 0, b = 0, a = 0, n = 0;
      for (let yy = y0; yy < y1 && yy < img.height; yy++) {
        for (let xx = x0; xx < x1 && xx < img.width; xx++) {
          const i = (yy * img.width + xx) * 4;
          const al = s[i + 3];
          r += s[i] * al; g += s[i + 1] * al; b += s[i + 2] * al; a += al; n++;
        }
      }
      const o = (y * w + x) * 4;
      if (a > 0) { d[o] = r / a; d[o + 1] = g / a; d[o + 2] = b / a; }
      d[o + 3] = n ? a / n : 0;
    }
  }
  return out;
}

// ---- colour space ----
function srgbToLin(c) { c /= 255; return c <= 0.04045 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4; }
const LIN = new Float32Array(256).map((_, i) => srgbToLin(i));
export function rgbToLab(r, g, b) {
  const R = LIN[r | 0], G = LIN[g | 0], B = LIN[b | 0];
  let x = (R * 0.4124 + G * 0.3576 + B * 0.1805) / 0.95047;
  let y = R * 0.2126 + G * 0.7152 + B * 0.0722;
  let z = (R * 0.0193 + G * 0.1192 + B * 0.9505) / 1.08883;
  const f = (t) => (t > 0.008856 ? Math.cbrt(t) : 7.787 * t + 16 / 116);
  x = f(x); y = f(y); z = f(z);
  return [116 * y - 16, 500 * (x - y), 200 * (y - z)];
}
export function labToRgb(L, a, b) {
  let y = (L + 16) / 116, x = a / 500 + y, z = y - b / 200;
  const fi = (t) => (t ** 3 > 0.008856 ? t ** 3 : (t - 16 / 116) / 7.787);
  x = fi(x) * 0.95047; y = fi(y); z = fi(z) * 1.08883;
  let R = x * 3.2406 + y * -1.5372 + z * -0.4986;
  let G = x * -0.9689 + y * 1.8758 + z * 0.0415;
  let B = x * 0.0557 + y * -0.204 + z * 1.057;
  const g = (c) => Math.round(255 * Math.min(1, Math.max(0, c <= 0.0031308 ? 12.92 * c : 1.055 * c ** (1 / 2.4) - 0.055)));
  return [g(R), g(G), g(B)];
}
export function hexToRgb(hex) {
  const h = hex.replace('#', '');
  return [parseInt(h.slice(0, 2), 16), parseInt(h.slice(2, 4), 16), parseInt(h.slice(4, 6), 16)];
}
export function rgbToHex(r, g, b) {
  return '#' + [r, g, b].map((v) => Math.round(v).toString(16).padStart(2, '0')).join('');
}
export function labDist(a, b) { return Math.hypot(a[0] - b[0], a[1] - b[1], a[2] - b[2]); }

// Edge-preserving smoothing (simple bilateral approximation in Lab), helps
// photos and JPEG-noisy logos quantise into clean regions.
export function smoothImage(img, radius = 2, colorSigma = 18) {
  const { width: w, height: h, data } = img;
  const lab = new Float32Array(w * h * 3);
  for (let i = 0; i < w * h; i++) {
    const [L, A, B] = rgbToLab(data[i * 4], data[i * 4 + 1], data[i * 4 + 2]);
    lab[i * 3] = L; lab[i * 3 + 1] = A; lab[i * 3 + 2] = B;
  }
  const out = makeImage(w, h);
  const inv2 = 1 / (2 * colorSigma * colorSigma);
  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) {
      const c = y * w + x;
      const L0 = lab[c * 3], A0 = lab[c * 3 + 1], B0 = lab[c * 3 + 2];
      let sr = 0, sg = 0, sb = 0, sw = 0;
      for (let dy = -radius; dy <= radius; dy++) {
        const yy = y + dy; if (yy < 0 || yy >= h) continue;
        for (let dx = -radius; dx <= radius; dx++) {
          const xx = x + dx; if (xx < 0 || xx >= w) continue;
          const n = yy * w + xx;
          if (data[n * 4 + 3] < 128) continue;
          const dl = lab[n * 3] - L0, da = lab[n * 3 + 1] - A0, db = lab[n * 3 + 2] - B0;
          const wgt = Math.exp(-(dl * dl + da * da + db * db) * inv2);
          sr += data[n * 4] * wgt; sg += data[n * 4 + 1] * wgt; sb += data[n * 4 + 2] * wgt; sw += wgt;
        }
      }
      const o = c * 4;
      if (sw > 0) { out.data[o] = sr / sw; out.data[o + 1] = sg / sw; out.data[o + 2] = sb / sw; }
      else { out.data[o] = data[o]; out.data[o + 1] = data[o + 1]; out.data[o + 2] = data[o + 2]; }
      out.data[o + 3] = data[o + 3];
    }
  }
  return out;
}

// Deterministic PRNG so the same picture always gives the same result.
function rng(seed) {
  let s = seed >>> 0 || 1;
  return () => ((s = (s * 1664525 + 1013904223) >>> 0) / 4294967296);
}

// k-means in Lab. Returns {palette: [[L,a,b]...], labels: Int16Array (-1 = transparent)}
export function quantize(img, k, { fixedPalette = null, seed = 7 } = {}) {
  const { width: w, height: h, data } = img;
  const N = w * h;
  const lab = new Float32Array(N * 3);
  const opaque = [];
  for (let i = 0; i < N; i++) {
    if (data[i * 4 + 3] < 128) continue;
    const [L, A, B] = rgbToLab(data[i * 4], data[i * 4 + 1], data[i * 4 + 2]);
    lab[i * 3] = L; lab[i * 3 + 1] = A; lab[i * 3 + 2] = B;
    opaque.push(i);
  }
  const labels = new Int16Array(N).fill(-1);
  if (!opaque.length) return { palette: [], labels };
  let centers;
  if (fixedPalette) {
    centers = fixedPalette.map((c) => c.slice());
  } else {
    const rand = rng(seed);
    const sample = [];
    const step = Math.max(1, Math.floor(opaque.length / 20000));
    for (let i = 0; i < opaque.length; i += step) sample.push(opaque[i]);
    k = Math.max(1, Math.min(k, sample.length));
    // k-means++ init
    centers = [];
    const first = sample[Math.floor(rand() * sample.length)];
    centers.push([lab[first * 3], lab[first * 3 + 1], lab[first * 3 + 2]]);
    const d2 = new Float32Array(sample.length).fill(Infinity);
    while (centers.length < k) {
      const c = centers[centers.length - 1];
      let sum = 0;
      for (let j = 0; j < sample.length; j++) {
        const p = sample[j] * 3;
        const d = (lab[p] - c[0]) ** 2 + (lab[p + 1] - c[1]) ** 2 + (lab[p + 2] - c[2]) ** 2;
        if (d < d2[j]) d2[j] = d;
        sum += d2[j];
      }
      if (sum === 0) break;
      let r = rand() * sum, pick = 0;
      for (let j = 0; j < sample.length; j++) { r -= d2[j]; if (r <= 0) { pick = j; break; } }
      const p = sample[pick] * 3;
      centers.push([lab[p], lab[p + 1], lab[p + 2]]);
    }
    for (let it = 0; it < 25; it++) {
      const acc = centers.map(() => [0, 0, 0, 0]);
      for (const i of sample) {
        const p = i * 3;
        let best = 0, bd = Infinity;
        for (let c = 0; c < centers.length; c++) {
          const C = centers[c];
          const d = (lab[p] - C[0]) ** 2 + (lab[p + 1] - C[1]) ** 2 + (lab[p + 2] - C[2]) ** 2;
          if (d < bd) { bd = d; best = c; }
        }
        const a = acc[best]; a[0] += lab[p]; a[1] += lab[p + 1]; a[2] += lab[p + 2]; a[3]++;
      }
      let moved = 0;
      centers = centers.map((C, c) => {
        const a = acc[c];
        if (!a[3]) return C;
        const n = [a[0] / a[3], a[1] / a[3], a[2] / a[3]];
        moved += labDist(n, C);
        return n;
      });
      if (moved < 0.1) break;
    }
  }
  for (const i of opaque) {
    const p = i * 3;
    let best = 0, bd = Infinity;
    for (let c = 0; c < centers.length; c++) {
      const C = centers[c];
      const d = (lab[p] - C[0]) ** 2 + (lab[p + 1] - C[1]) ** 2 + (lab[p + 2] - C[2]) ** 2;
      if (d < bd) { bd = d; best = c; }
    }
    labels[i] = best;
  }
  return { palette: centers, labels };
}

// Gentle clean-up for logos: only a pixel with no same-colour neighbour at all (a lone speck) changes,
// so 1-2 pixel strokes of small letters stay intact.
export function despeckle(labels, w, h) {
  const out = new Int16Array(labels);
  for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) {
    const i = y * w + x, l = labels[i];
    let same = 0;
    const votes = new Map();
    for (let dy = -1; dy <= 1; dy++) for (let dx = -1; dx <= 1; dx++) {
      if (!dx && !dy) continue;
      const X = x + dx, Y = y + dy;
      if (X < 0 || Y < 0 || X >= w || Y >= h) continue;
      const m = labels[Y * w + X];
      if (m === l) same++; else votes.set(m, (votes.get(m) || 0) + 1);
    }
    if (same === 0 && votes.size) { let best = l, bc = -1; for (const [m, n] of votes) if (n > bc) { bc = n; best = m; } out[i] = best; }
  }
  return out;
}

// Majority (mode) filter on labels – removes jagged single pixels on edges.
export function modeFilter(labels, w, h, iterations = 1) {
  let cur = labels;
  for (let it = 0; it < iterations; it++) {
    const out = new Int16Array(cur.length);
    for (let y = 0; y < h; y++) {
      for (let x = 0; x < w; x++) {
        const counts = new Map();
        for (let dy = -1; dy <= 1; dy++) {
          const yy = y + dy; if (yy < 0 || yy >= h) continue;
          for (let dx = -1; dx <= 1; dx++) {
            const xx = x + dx; if (xx < 0 || xx >= w) continue;
            const l = cur[yy * w + xx];
            counts.set(l, (counts.get(l) || 0) + 1);
          }
        }
        const own = cur[y * w + x];
        let best = own, bc = counts.get(own);
        for (const [l, c] of counts) if (c > bc + 1) { best = l; bc = c; }
        out[y * w + x] = best;
      }
    }
    cur = out;
  }
  return cur;
}

// Connected components (4-connectivity) of a label image.
export function components(labels, w, h) {
  const comp = new Int32Array(w * h).fill(-1);
  const info = [];
  const stack = new Int32Array(w * h);
  for (let i = 0; i < w * h; i++) {
    if (comp[i] !== -1) continue;
    const l = labels[i];
    const id = info.length;
    let sp = 0, size = 0;
    stack[sp++] = i; comp[i] = id;
    while (sp) {
      const p = stack[--sp]; size++;
      const x = p % w, y = (p - x) / w;
      if (x > 0 && comp[p - 1] === -1 && labels[p - 1] === l) { comp[p - 1] = id; stack[sp++] = p - 1; }
      if (x < w - 1 && comp[p + 1] === -1 && labels[p + 1] === l) { comp[p + 1] = id; stack[sp++] = p + 1; }
      if (y > 0 && comp[p - w] === -1 && labels[p - w] === l) { comp[p - w] = id; stack[sp++] = p - w; }
      if (y < h - 1 && comp[p + w] === -1 && labels[p + w] === l) { comp[p + w] = id; stack[sp++] = p + w; }
    }
    info.push({ label: l, size, seed: i });
  }
  return { comp, info };
}

// Merge regions smaller than minPixels into their dominant neighbour.
export function removeSmallRegions(labels, w, h, minPixels, { keepTransparent = true } = {}) {
  let cur = labels;
  for (let pass = 0; pass < 4; pass++) {
    const { comp, info } = components(cur, w, h);
    let changed = false;
    const out = new Int16Array(cur);
    const neighbourVotes = new Map();
    for (let p = 0; p < w * h; p++) {
      const c = comp[p];
      if (info[c].size >= minPixels) continue;
      if (keepTransparent && info[c].label === -1 && info[c].size >= 4) continue;
      const x = p % w, y = (p - x) / w;
      const nb = [];
      if (x > 0) nb.push(p - 1);
      if (x < w - 1) nb.push(p + 1);
      if (y > 0) nb.push(p - w);
      if (y < h - 1) nb.push(p + w);
      for (const q of nb) {
        if (comp[q] === c) continue;
        let m = neighbourVotes.get(c);
        if (!m) neighbourVotes.set(c, (m = new Map()));
        const l = cur[q];
        m.set(l, (m.get(l) || 0) + 1);
      }
    }
    const target = new Map();
    for (const [c, m] of neighbourVotes) {
      let best = null, bc = -1;
      for (const [l, n] of m) if (n > bc) { bc = n; best = l; }
      target.set(c, best);
    }
    for (let p = 0; p < w * h; p++) {
      const t = target.get(comp[p]);
      if (t !== undefined && t !== null && out[p] !== t) { out[p] = t; changed = true; }
    }
    cur = out;
    if (!changed) break;
  }
  return cur;
}

// Distance transform (chamfer 3-4) of a binary mask, result in pixels.
export function distanceTransform(mask, w, h) {
  const INF = 1e9;
  const d = new Float32Array(w * h);
  for (let i = 0; i < w * h; i++) d[i] = mask[i] ? INF : 0;
  const at = (x, y) => (x < 0 || y < 0 || x >= w || y >= h ? 0 : d[y * w + x]);
  for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) {
    const i = y * w + x; if (!d[i]) continue;
    d[i] = Math.min(d[i], at(x - 1, y) + 3, at(x, y - 1) + 3, at(x - 1, y - 1) + 4, at(x + 1, y - 1) + 4);
  }
  for (let y = h - 1; y >= 0; y--) for (let x = w - 1; x >= 0; x--) {
    const i = y * w + x; if (!d[i]) continue;
    d[i] = Math.min(d[i], at(x + 1, y) + 3, at(x, y + 1) + 3, at(x + 1, y + 1) + 4, at(x - 1, y + 1) + 4);
  }
  for (let i = 0; i < w * h; i++) d[i] /= 3;
  return d;
}

// Trace the boundary loops of a binary mask by crack following.
// Returns rings in pixel-corner coordinates (only corner vertices kept).
export function traceMask(mask, w, h) {
  const W1 = w + 1;
  // up to two outgoing edges per vertex; store target vertex and direction
  const out1 = new Int32Array(W1 * (h + 1)).fill(-1);
  const out2 = new Int32Array(W1 * (h + 1)).fill(-1);
  const used1 = new Uint8Array(W1 * (h + 1));
  const used2 = new Uint8Array(W1 * (h + 1));
  const m = (x, y) => x >= 0 && y >= 0 && x < w && y < h && mask[y * w + x];
  const add = (x0, y0, x1, y1) => {
    const a = y0 * W1 + x0, b = y1 * W1 + x1;
    if (out1[a] === -1) out1[a] = b; else out2[a] = b;
  };
  let edgeCount = 0;
  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) {
      if (!mask[y * w + x]) continue;
      if (!m(x, y - 1)) { add(x, y, x + 1, y); edgeCount++; }
      if (!m(x + 1, y)) { add(x + 1, y, x + 1, y + 1); edgeCount++; }
      if (!m(x, y + 1)) { add(x + 1, y + 1, x, y + 1); edgeCount++; }
      if (!m(x - 1, y)) { add(x, y + 1, x, y); edgeCount++; }
    }
  }
  const rings = [];
  if (!edgeCount) return rings;
  const vx = (v) => v % W1, vy = (v) => (v - (v % W1)) / W1;
  for (let start = 0; start < out1.length; start++) {
    for (const which of [1, 2]) {
      const arr = which === 1 ? out1 : out2;
      const used = which === 1 ? used1 : used2;
      if (arr[start] === -1 || used[start]) continue;
      // follow loop
      const pts = [];
      let v = start, nxt = arr[start];
      used[start] = 1;
      let pdx = vx(nxt) - vx(v), pdy = vy(nxt) - vy(v);
      pts.push({ x: vx(v), y: vy(v) });
      let guard = 0;
      while (guard++ < 8 * edgeCount + 8) {
        v = nxt;
        // choose outgoing: prefer right turn, then straight, then left
        let choice = -1, cw = 0;
        const cands = [];
        if (out1[v] !== -1 && !used1[v]) cands.push([out1[v], 1]);
        if (out2[v] !== -1 && !used2[v]) cands.push([out2[v], 2]);
        if (!cands.length) break;
        if (cands.length === 1) { [choice, cw] = cands[0]; }
        else {
          const rx = -pdy, ry = pdx; // right turn in y-down coordinates
          let best = -Infinity;
          for (const [t, wch] of cands) {
            const dx = vx(t) - vx(v), dy = vy(t) - vy(v);
            const score = dx * rx + dy * ry; // 1 = right, 0 = straight, -1 = left
            if (score > best) { best = score; choice = t; cw = wch; }
          }
        }
        if (cw === 1) used1[v] = 1; else used2[v] = 1;
        const dx = vx(choice) - vx(v), dy = vy(choice) - vy(v);
        if (dx !== pdx || dy !== pdy) pts.push({ x: vx(v), y: vy(v) });
        pdx = dx; pdy = dy;
        nxt = choice;
        if (v === start && choice === arr[start]) break;
        if (nxt === start) {
          // closing; check if start vertex is a corner
          break;
        }
      }
      if (pts.length >= 3) rings.push(pts);
    }
  }
  return rings;
}

// Majority label on the image border -> likely background.
export function borderLabel(labels, w, h) {
  const counts = new Map();
  let total = 0;
  const add = (i) => { const l = labels[i]; counts.set(l, (counts.get(l) || 0) + 1); total++; };
  for (let x = 0; x < w; x++) { add(x); add((h - 1) * w + x); }
  for (let y = 0; y < h; y++) { add(y * w); add(y * w + w - 1); }
  let best = null, bc = 0;
  for (const [l, c] of counts) if (c > bc) { bc = c; best = l; }
  return { label: best, share: bc / Math.max(1, total) };
}

export function toGray(img) {
  const g = new Float32Array(img.width * img.height);
  for (let i = 0; i < g.length; i++) {
    const a = img.data[i * 4 + 3] / 255;
    const v = (0.299 * img.data[i * 4] + 0.587 * img.data[i * 4 + 1] + 0.114 * img.data[i * 4 + 2]) / 255;
    g[i] = a < 0.5 ? -1 : v;
  }
  return g;
}

// Merge regions that are thinner than minHalfWidthPx (e.g. anti-aliased edge rings)
// into the neighbouring region they share the longest border with.
export function mergeThinRegions(labels, w, h, minHalfWidthPx, { protect = null } = {}) {
  const INF = 1e9;
  const d = new Float32Array(w * h);
  for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) {
    const i = y * w + x, l = labels[i];
    const edge = (x > 0 && labels[i - 1] !== l) || (x < w - 1 && labels[i + 1] !== l) || (y > 0 && labels[i - w] !== l) || (y < h - 1 && labels[i + w] !== l);
    d[i] = edge ? 1 : INF;
  }
  const at = (x, y) => (x < 0 || y < 0 || x >= w || y >= h ? INF : d[y * w + x]);
  for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) { const i = y * w + x; d[i] = Math.min(d[i], at(x - 1, y) + 1, at(x, y - 1) + 1, at(x - 1, y - 1) + 1.414, at(x + 1, y - 1) + 1.414); }
  for (let y = h - 1; y >= 0; y--) for (let x = w - 1; x >= 0; x--) { const i = y * w + x; d[i] = Math.min(d[i], at(x + 1, y) + 1, at(x, y + 1) + 1, at(x + 1, y + 1) + 1.414, at(x - 1, y + 1) + 1.414); }
  const { comp, info } = components(labels, w, h);
  const maxD = new Float32Array(info.length);
  for (let i = 0; i < w * h; i++) if (d[i] < INF && d[i] > maxD[comp[i]]) maxD[comp[i]] = d[i];
  const votes = new Map();
  for (let p = 0; p < w * h; p++) {
    const c = comp[p];
    if (maxD[c] >= minHalfWidthPx || info[c].label < 0) continue;
    if (protect && protect(info[c])) continue;
    const x = p % w, y = (p - x) / w;
    for (const q of [x > 0 ? p - 1 : -1, x < w - 1 ? p + 1 : -1, y > 0 ? p - w : -1, y < h - 1 ? p + w : -1]) {
      if (q < 0 || comp[q] === c) continue;
      let m = votes.get(c); if (!m) votes.set(c, (m = new Map()));
      m.set(labels[q], (m.get(labels[q]) || 0) + 1);
    }
  }
  const out = new Int16Array(labels);
  const target = new Map();
  for (const [c, m] of votes) { let best = null, bc = -1; for (const [l, n] of m) if (n > bc) { bc = n; best = l; } target.set(c, best); }
  for (let p = 0; p < w * h; p++) { const t = target.get(comp[p]); if (t !== undefined && t !== null) out[p] = t; }
  return out;
}
