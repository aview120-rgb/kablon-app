// Copies the onnxruntime WebAssembly files next to the app so AI works offline.
import fs from 'fs';
import path from 'path';
const root = path.resolve(path.dirname(new URL(import.meta.url).pathname), '..');
const src = path.join(root, 'node_modules/onnxruntime-web/dist');
const dst = path.join(root, 'public/ort');
fs.mkdirSync(dst, { recursive: true });
for (const f of ['ort-wasm-simd-threaded.wasm', 'ort-wasm-simd-threaded.mjs']) fs.copyFileSync(path.join(src, f), path.join(dst, f));
console.log('onnxruntime wasm copied');
