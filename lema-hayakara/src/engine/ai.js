// On-device AI background removal (ISNet / U2-Net via onnxruntime-web).
// Everything runs locally – no network is used.
let ortPromise = null;
const sessions = new Map();

export const AI_MODELS = {
  quality: { file: 'isnet-general-use.onnx', size: 1024, mean: [0.5, 0.5, 0.5], std: [1, 1, 1], tta: true, label: 'איכות גבוהה (איטי יותר)' },
  people: { file: 'u2net_human_seg.onnx', size: 320, mean: [0.485, 0.456, 0.406], std: [0.229, 0.224, 0.225], tta: true, label: 'אנשים' },
  fast: { file: 'u2netp.onnx', size: 320, mean: [0.485, 0.456, 0.406], std: [0.229, 0.224, 0.225], label: 'מהיר' },
};

async function getOrt(base) {
  if (!ortPromise) {
    ortPromise = import('onnxruntime-web/wasm').then((ort) => {
      ort.env.wasm.wasmPaths = base + 'ort/';
      const cores = (typeof navigator !== 'undefined' && navigator.hardwareConcurrency) || 2;
      ort.env.wasm.numThreads = typeof crossOriginIsolated !== 'undefined' && crossOriginIsolated ? Math.min(4, cores) : 1;
      return ort;
    });
  }
  return ortPromise;
}

async function getSession(kind, base, onProgress) {
  const key = kind;
  if (sessions.has(key)) return sessions.get(key);
  const p = (async () => {
    const ort = await getOrt(base);
    onProgress?.('טוען את מנוע הבינה המלאכותית…');
    const res = await fetch(base + 'models/' + AI_MODELS[kind].file);
    const buf = new Uint8Array(await res.arrayBuffer());
    const lowMem = false;
    return ort.InferenceSession.create(buf, { executionProviders: ['wasm'], graphOptimizationLevel: lowMem ? 'basic' : 'all', enableCpuMemArena: !lowMem, enableMemPattern: !lowMem, executionMode: 'sequential' });
  })();
  sessions.set(key, p);
  p.catch(() => sessions.delete(key));
  return p;
}

/**
 * Remove the background of an image (ImageData-like). Returns a new image with alpha.
 * base: URL prefix where /models and /ort live.
 */
export async function removeBackground(img, { kind = 'quality', base = './', onProgress, threshold = 0.5 } = {}) {
  const m = AI_MODELS[kind];
  const ort = await getOrt(base);
  const session = await getSession(kind, base, onProgress);
  onProgress?.('הבינה המלאכותית מזהה את האובייקט…');
  const S = m.size;
  // resize to S x S (box sampling) and normalise
  const input = new Float32Array(3 * S * S);
  const sx = img.width / S, sy = img.height / S;
  let maxV = 1;
  for (let i = 0; i < img.data.length; i += 4) maxV = Math.max(maxV, img.data[i], img.data[i + 1], img.data[i + 2]);
  for (let y = 0; y < S; y++) {
    for (let x = 0; x < S; x++) {
      const ix = Math.min(img.width - 1, Math.floor((x + 0.5) * sx));
      const iy = Math.min(img.height - 1, Math.floor((y + 0.5) * sy));
      const o = (iy * img.width + ix) * 4;
      const a = img.data[o + 3] / 255;
      for (let c = 0; c < 3; c++) {
        const v = (img.data[o + c] * a + 255 * (1 - a)) / maxV;
        input[c * S * S + y * S + x] = (v - m.mean[c]) / m.std[c];
      }
    }
  }
  const feeds = { [session.inputNames[0]]: new ort.Tensor('float32', input, [1, 3, S, S]) };
  const out = await session.run(feeds, [session.outputNames[0]]);
  let pred = Float32Array.from(out[session.outputNames[0]].data);
  if (m.tta) {
    // second pass on the mirrored picture, averaged: steadier, cleaner edges
    onProgress?.('בודק שוב לדיוק מרבי…');
    const flipped = new Float32Array(input.length);
    for (let c = 0; c < 3; c++) for (let y = 0; y < S; y++) for (let x = 0; x < S; x++) flipped[c * S * S + y * S + x] = input[c * S * S + y * S + (S - 1 - x)];
    const out2 = await session.run({ [session.inputNames[0]]: new ort.Tensor('float32', flipped, [1, 3, S, S]) }, [session.outputNames[0]]);
    const p2 = out2[session.outputNames[0]].data;
    const norm = (arr) => { let a = Infinity, b = -Infinity; for (const v of arr) { if (v < a) a = v; if (v > b) b = v; } return [a, (b - a) || 1]; };
    const [a1, r1] = norm(pred), [a2, r2] = norm(p2);
    for (let y = 0; y < S; y++) for (let x = 0; x < S; x++) {
      const i = y * S + x;
      pred[i] = ((pred[i] - a1) / r1 + (p2[y * S + (S - 1 - x)] - a2) / r2) / 2;
    }
  }
  if (m.sigmoid) pred = Float32Array.from(pred, (v) => 1 / (1 + Math.exp(-v)));
  let mn = Infinity, mx = -Infinity;
  for (let i = 0; i < pred.length; i++) { if (pred[i] < mn) mn = pred[i]; if (pred[i] > mx) mx = pred[i]; }
  const range = mx - mn || 1;
  cleanMask(pred, S, mn, range, threshold);
  // upsample mask (bilinear) and apply as alpha
  const res = { width: img.width, height: img.height, data: new Uint8ClampedArray(img.data) };
  for (let y = 0; y < img.height; y++) {
    const fy = Math.min(S - 1, Math.max(0, ((y + 0.5) / img.height) * S - 0.5));
    const y0 = Math.floor(fy), y1 = Math.min(S - 1, y0 + 1), ty = fy - y0;
    for (let x = 0; x < img.width; x++) {
      const fx = Math.min(S - 1, Math.max(0, ((x + 0.5) / img.width) * S - 0.5));
      const x0 = Math.floor(fx), x1 = Math.min(S - 1, x0 + 1), tx = fx - x0;
      const v = (pred[y0 * S + x0] * (1 - tx) + pred[y0 * S + x1] * tx) * (1 - ty) + (pred[y1 * S + x0] * (1 - tx) + pred[y1 * S + x1] * tx) * ty;
      const a = (v - mn) / range;
      const o = (y * img.width + x) * 4 + 3;
      res.data[o] = Math.min(res.data[o], a >= threshold ? 255 : 0);
    }
  }
  return res;
}

// Keep the main object(s), drop specks and fill small holes in the AI mask (in place).
function cleanMask(pred, S, mn, range, thr) {
  const N = S * S;
  const on = new Uint8Array(N);
  for (let i = 0; i < N; i++) on[i] = (pred[i] - mn) / range >= thr ? 1 : 0;
  const label = new Int32Array(N).fill(-1);
  const sizes = [], touches = [];
  const stack = new Int32Array(N);
  for (let i = 0; i < N; i++) {
    if (label[i] !== -1) continue;
    const v = on[i], id = sizes.length;
    let sp = 0, size = 0, edge = false;
    stack[sp++] = i; label[i] = id;
    while (sp) {
      const q = stack[--sp]; size++;
      const x = q % S, y = (q - x) / S;
      if (x === 0 || y === 0 || x === S - 1 || y === S - 1) edge = true;
      if (x > 0 && label[q - 1] === -1 && on[q - 1] === v) { label[q - 1] = id; stack[sp++] = q - 1; }
      if (x < S - 1 && label[q + 1] === -1 && on[q + 1] === v) { label[q + 1] = id; stack[sp++] = q + 1; }
      if (y > 0 && label[q - S] === -1 && on[q - S] === v) { label[q - S] = id; stack[sp++] = q - S; }
      if (y < S - 1 && label[q + S] === -1 && on[q + S] === v) { label[q + S] = id; stack[sp++] = q + S; }
    }
    sizes.push({ v, size }); touches.push(edge);
  }
  let biggest = 0;
  for (const c of sizes) if (c.v && c.size > biggest) biggest = c.size;
  const hi = mn + range, lo = mn;
  for (let i = 0; i < N; i++) {
    const c = sizes[label[i]];
    if (c.v && c.size < biggest * 0.04) pred[i] = lo;                                   // speck
    else if (!c.v && !touches[label[i]] && c.size < N * 0.004) pred[i] = hi;            // small hole
  }
}
