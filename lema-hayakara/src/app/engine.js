// Promise-based bridge to the background worker.
const worker = new Worker(new URL('../worker.js', import.meta.url), { type: 'module' });
let seq = 0;
const pending = new Map();
let progressCb = null;
worker.onmessage = (e) => {
  const { id, result, error, progress } = e.data;
  if (progress) { progressCb?.(progress); return; }
  const p = pending.get(id);
  if (!p) return;
  pending.delete(id);
  if (error) p.reject(new Error(error)); else p.resolve(result);
};
worker.onerror = (e) => { for (const p of pending.values()) p.reject(new Error(e.message || 'worker error')); pending.clear(); };
export function call(cmd, args, transfer = []) {
  return new Promise((resolve, reject) => {
    const id = ++seq;
    pending.set(id, { resolve, reject });
    worker.postMessage({ id, cmd, args }, transfer);
  });
}
export function onProgress(cb) { progressCb = cb; }
