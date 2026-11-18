import { defineConfig } from 'vite';
const stamp = process.env.SAFA_BUILD || new Date().toISOString().slice(0, 16).replace('T', ' ');
export default defineConfig({ base: './', build: { outDir: 'dist', target: 'es2020' }, define: { __SAFA_BUILD__: JSON.stringify(stamp) } });
