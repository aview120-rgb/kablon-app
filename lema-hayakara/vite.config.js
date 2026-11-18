import { defineConfig } from 'vite';
import fs from 'fs';
const pkg = JSON.parse(fs.readFileSync(new URL('./package.json', import.meta.url), 'utf8'));
export default defineConfig({
  base: './',
  define: { __APP_VERSION__: JSON.stringify(pkg.version) },
  worker: { format: 'es' },
  esbuild: { target: 'es2022' },
  build: { target: 'es2022', outDir: 'dist', assetsInlineLimit: 0, chunkSizeWarningLimit: 4000 },
  optimizeDeps: { exclude: ['onnxruntime-web', 'harfbuzzjs'] },
  server: { headers: { 'Cross-Origin-Opener-Policy': 'same-origin', 'Cross-Origin-Embedder-Policy': 'require-corp' } },
});
