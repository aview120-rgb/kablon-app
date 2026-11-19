// Background worker: all heavy embroidery work runs here so the screen never freezes.
import { digitizeImage, compileDesign, centerObjects, mergeSimilarColors } from './engine/design.js';
import { photoLines } from './engine/photo.js';
import { setFontLoader, textToShapes, layoutText } from './engine/text.js';
import { removeBackground } from './engine/ai.js';
import { readDST, readEXP, readPES, readJEF } from './engine/formats.js';
import { patternToObjects } from './engine/design.js';
import { makeBackground } from './engine/background.js';
import { libraryShapes, outlineBand } from './engine/library.js';

const BASE = new URL('./', self.location.href.replace(/assets\/[^/]*$/, '')).href;
const ROOT = (() => {
  // worker lives in /assets/ after build, in /src/ during dev
  const href = self.location.href;
  const i = href.lastIndexOf('/assets/');
  if (i >= 0) return href.slice(0, i + 1);
  const j = href.lastIndexOf('/src/');
  if (j >= 0) return href.slice(0, j + 1);
  return BASE;
})();

setFontLoader(async (file) => {
  const r = await fetch(ROOT + 'fonts/' + file);
  if (!r.ok) throw new Error('font ' + file);
  return new Uint8Array(await r.arrayBuffer());
});

function packCompiled(comp) {
  const transfer = [];
  const blocks = comp.blocks.map((b) => {
    const xy = new Float32Array(b.points.length * 2);
    const flags = new Uint8Array(b.points.length);
    b.points.forEach((p, i) => { xy[i * 2] = p.x; xy[i * 2 + 1] = p.y; flags[i] = (p.j ? 1 : 0) | (p.t ? 2 : 0); });
    transfer.push(xy.buffer, flags.buffer);
    return { color: b.color, objectIds: b.objectIds, xy, flags, name: b.name, forced: b.forced };
  });
  return { result: { blocks }, transfer };
}

const handlers = {
  async digitize({ img, opts }) {
    const d = digitizeImage(img, opts);
    let objects = centerObjects(d.objects);
    if (opts.maxColors) objects = mergeSimilarColors(objects, opts.maxColors);
    return { result: { ...d, objects } };
  },
  async photo({ img, opts }) {
    const d = photoLines(img, opts);
    return { result: { ...d, objects: centerObjects(d.objects) } };
  },
  async layout({ text, opts }) {
    return { result: await layoutText(text, opts) };
  },
  async text({ text, opts }) {
    const r = await textToShapes(text, opts);
    return { result: r };
  },
  async compile({ objects, settings }) {
    return packCompiled(compileDesign(objects, settings));
  },
  async removeBg({ img, kind }) {
    const res = await removeBackground(img, {
      kind, base: ROOT,
      onProgress: (msg) => self.postMessage({ progress: msg }),
    });
    return { result: res, transfer: [res.data.buffer] };
  },
  async libShape({ id, widthMm }) {
    return { result: libraryShapes(id, widthMm) };
  },
  async outline({ shapes, widthMm }) {
    return { result: outlineBand(shapes, widthMm, 0.4) };
  },
  async background({ objects, spec }) {
    return { result: makeBackground(objects, spec) };
  },
  async importFile({ bytes, ext, palette }) {
    const pat = ext === 'exp' ? readEXP(bytes) : ext === 'pes' || ext === 'pec' ? readPES(bytes) : ext === 'jef' ? readJEF(bytes) : readDST(bytes);
    const objects = centerObjects(patternToObjects(pat, palette));
    return { result: { objects, name: pat.name } };
  },
};

self.onmessage = async (e) => {
  const { id, cmd, args } = e.data;
  try {
    const { result, transfer } = await handlers[cmd](args);
    self.postMessage({ id, result }, transfer || []);
  } catch (err) {
    self.postMessage({ id, error: String(err && err.message ? err.message : err) });
  }
};
