// Turns any file the user picks into something the engine understands.
import * as pdfjs from 'pdfjs-dist';
import pdfWorkerUrl from 'pdfjs-dist/build/pdf.worker.min.mjs?url';

pdfjs.GlobalWorkerOptions.workerSrc = pdfWorkerUrl;

export const EMB_EXT = ['dst', 'exp'];
const MAX_SIDE = 2200;

function canvasToImage(canvas) {
  const ctx = canvas.getContext('2d', { willReadFrequently: true });
  const d = ctx.getImageData(0, 0, canvas.width, canvas.height);
  return { width: d.width, height: d.height, data: d.data };
}

async function bitmapToImage(src, w, h) {
  const k = Math.min(1, MAX_SIDE / Math.max(w, h));
  const c = document.createElement('canvas');
  c.width = Math.max(1, Math.round(w * k)); c.height = Math.max(1, Math.round(h * k));
  c.getContext('2d').drawImage(src, 0, 0, c.width, c.height);
  return { img: canvasToImage(c), preview: c.toDataURL('image/png') };
}

function loadImgElement(url) {
  return new Promise((res, rej) => {
    const im = new Image();
    im.onload = () => res(im);
    im.onerror = () => rej(new Error('לא הצלחנו לפתוח את התמונה'));
    im.src = url;
  });
}

async function svgToImage(text) {
  // render the SVG big enough for clean tracing
  const blob = new Blob([text], { type: 'image/svg+xml' });
  const url = URL.createObjectURL(blob);
  try {
    const im = await loadImgElement(url);
    let w = im.naturalWidth || 1000, h = im.naturalHeight || 1000;
    const k = 1600 / Math.max(w, h);
    w *= k; h *= k;
    return bitmapToImage(im, w, h);
  } finally { URL.revokeObjectURL(url); }
}

export async function pdfPageCount(bytes) {
  const doc = await pdfjs.getDocument({ data: bytes.slice(0) }).promise;
  return doc.numPages;
}

async function pdfToImage(bytes, page = 1) {
  const doc = await pdfjs.getDocument({ data: bytes }).promise;
  const p = await doc.getPage(Math.min(page, doc.numPages));
  const vp0 = p.getViewport({ scale: 1 });
  const scale = Math.min(4, 1800 / Math.max(vp0.width, vp0.height));
  const vp = p.getViewport({ scale });
  const c = document.createElement('canvas');
  c.width = Math.round(vp.width); c.height = Math.round(vp.height);
  const ctx = c.getContext('2d');
  ctx.fillStyle = '#ffffff'; ctx.fillRect(0, 0, c.width, c.height);
  await p.render({ canvasContext: ctx, viewport: vp }).promise;
  const r = await bitmapToImage(c, c.width, c.height);
  return { ...r, pages: doc.numPages };
}

/**
 * Returns {kind:'image', img, preview, name} or {kind:'embroidery', bytes, ext, name}
 */
export async function importFile(file, { pdfPage = 1 } = {}) {
  const name = file.name || 'design';
  const ext = (name.split('.').pop() || '').toLowerCase();
  const base = name.replace(/\.[^.]+$/, '');
  if (EMB_EXT.includes(ext)) {
    return { kind: 'embroidery', bytes: new Uint8Array(await file.arrayBuffer()), ext, name: base };
  }
  if (ext === 'pdf' || file.type === 'application/pdf') {
    const r = await pdfToImage(new Uint8Array(await file.arrayBuffer()), pdfPage);
    return { kind: 'image', ...r, name: base };
  }
  if (ext === 'svg' || file.type === 'image/svg+xml') {
    return { kind: 'image', ...(await svgToImage(await file.text())), name: base };
  }
  if (['pes', 'jef', 'emb', 'hus', 'vp3', 'xxx', 'pec'].includes(ext)) {
    throw new Error('אפשר לפתוח קבצי רקמה מסוג DST ו-EXP. קבצים מסוג ' + ext.toUpperCase() + ' עדיין לא נתמכים לפתיחה.');
  }
  if (['heic', 'heif'].includes(ext)) throw new Error('תמונות HEIC (מאייפון) לא נתמכות. שמרו את התמונה כ-JPG ונסו שוב.');
  if (['tif', 'tiff'].includes(ext)) throw new Error('קובצי TIFF לא נתמכים. שמרו את התמונה כ-PNG או JPG ונסו שוב.');
  // any other image the system can decode (jpg, png, webp, gif, bmp, avif, ico…)
  try {
    const bmp = await createImageBitmap(file);
    const r = await bitmapToImage(bmp, bmp.width, bmp.height);
    bmp.close?.();
    return { kind: 'image', ...r, name: base };
  } catch {
    const url = URL.createObjectURL(file);
    try {
      const im = await loadImgElement(url);
      return { kind: 'image', ...(await bitmapToImage(im, im.naturalWidth, im.naturalHeight)), name: base };
    } finally { URL.revokeObjectURL(url); }
  }
}

export function dataUrlToImage(url) {
  return loadImgElement(url).then((im) => bitmapToImage(im, im.naturalWidth, im.naturalHeight));
}
