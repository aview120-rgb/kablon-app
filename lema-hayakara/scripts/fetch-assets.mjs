// Downloads fonts and AI models (too big for git) and verifies their checksums.
// An item is either a plain file ({ path, url, sha256 }) or a file inside a .tar.gz
// ({ path, sha256, archive: { url, sha256, member } }) – both the archive and the file are verified.
import fs from 'fs';
import path from 'path';
import zlib from 'zlib';
import { fileURLToPath } from 'url';
import crypto from 'crypto';
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const items = JSON.parse(fs.readFileSync(path.join(root, 'scripts/assets.json'), 'utf8'));
const sha = (b) => crypto.createHash('sha256').update(b).digest('hex');

async function download(url, want) {
  for (let attempt = 1; ; attempt++) {
    try {
      const r = await fetch(url, { redirect: 'follow' });
      if (!r.ok) throw new Error('HTTP ' + r.status);
      const b = Buffer.from(await r.arrayBuffer());
      if (sha(b) !== want) throw new Error('checksum mismatch');
      return b;
    } catch (e) {
      if (attempt >= 5) { console.error('FAILED', url, e.message); process.exit(1); }
      await new Promise((res) => setTimeout(res, 2500 * attempt));
    }
  }
}

// minimal ustar reader
function untar(buf) {
  const files = new Map();
  for (let off = 0; off + 512 <= buf.length;) {
    const hdr = buf.subarray(off, off + 512);
    if (hdr.every((x) => x === 0)) break;
    const str = (a, b) => hdr.subarray(a, b).toString('utf8').replace(/\0.*$/s, '');
    const name = (str(345, 500) ? str(345, 500) + '/' : '') + str(0, 100);
    const size = parseInt(str(124, 136).trim() || '0', 8);
    const type = String.fromCharCode(hdr[156] || 48);
    off += 512;
    if (type === '0' || type === '\0') files.set(name, buf.subarray(off, off + size));
    off += Math.ceil(size / 512) * 512;
  }
  return files;
}

const archives = new Map();
let fetched = 0;
for (const it of items) {
  const p = path.join(root, it.path);
  if (fs.existsSync(p) && sha(fs.readFileSync(p)) === it.sha256) continue;
  fs.mkdirSync(path.dirname(p), { recursive: true });
  let b;
  if (it.archive) {
    if (!archives.has(it.archive.url)) archives.set(it.archive.url, untar(zlib.gunzipSync(await download(it.archive.url, it.archive.sha256))));
    b = archives.get(it.archive.url).get(it.archive.member);
    if (!b) { console.error('FAILED', it.archive.member, 'not in archive'); process.exit(1); }
    if (sha(b) !== it.sha256) { console.error('FAILED', it.archive.member, 'checksum mismatch'); process.exit(1); }
  } else b = await download(it.url, it.sha256);
  fs.writeFileSync(p, b);
  fetched++;
  console.log('fetched', it.path, (b.length / 1e6).toFixed(2) + 'MB');
}
console.log('assets ready, downloaded', fetched);
