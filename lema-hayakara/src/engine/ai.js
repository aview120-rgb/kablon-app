// On-device AI background removal (ISNet / U2-Net via onnxruntime-web).
// Everything runs locally – no network is used.
let ortPromise = null;
const sessions = new Map();

export const AI_MODELS = {
  quality: { file: 'isnet-general-use.onnx', size: 1024, mean: [0.5, 0.5, 0.5], std: [1, 1, 1], label: 'איכות גבוהה (איטי יותר)' },
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
    return ort.InferenceSession.create(buf, { executionProviders: ['wasm'], graphOptimizationLevel: 'all' });
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
  const pred = out[session.outputNames[0]].data;
  let mn = Infinity, mx = -Infinity;
  for (let i = 0; i < pred.length; i++) { if (pred[i] < mn) mn = pred[i]; if (pred[i] > mx) mx = pred[i]; }
  const range = mx - mn || 1;
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
