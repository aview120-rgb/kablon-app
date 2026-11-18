// Downloads fonts and AI models (too big for git) and verifies their checksums.
import fs from 'fs';
import path from 'path';
import crypto from 'crypto';
const root = path.resolve(path.dirname(new URL(import.meta.url).pathname), '..');
const items = JSON.parse(fs.readFileSync(path.join(root, 'scripts/assets.json'), 'utf8'));
const sha = (b) => crypto.createHash('sha256').update(b).digest('hex');
let fetched = 0;
for (const it of items) {
  const p = path.join(root, it.path);
  if (fs.existsSync(p) && sha(fs.readFileSync(p)) === it.sha256) continue;
  fs.mkdirSync(path.dirname(p), { recursive: true });
  for (let attempt = 1; ; attempt++) {
    try {
      const r = await fetch(it.url);
      if (!r.ok) throw new Error('HTTP ' + r.status);
      const b = Buffer.from(await r.arrayBuffer());
      if (sha(b) !== it.sha256) throw new Error('checksum mismatch');
      fs.writeFileSync(p, b);
      fetched++;
      console.log('fetched', it.path, (b.length / 1e6).toFixed(1) + 'MB');
      break;
    } catch (e) {
      if (attempt >= 4) { console.error('FAILED', it.url, e.message); process.exit(1); }
      await new Promise((res) => setTimeout(res, 2000 * attempt));
    }
  }
}
console.log('assets ready, downloaded', fetched);
