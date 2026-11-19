// Design model + image digitizing + compilation into a machine pattern.
import {
  quantize, modeFilter, removeSmallRegions, mergeThinRegions, components, traceMask, borderLabel,
  labToRgb, rgbToHex, hexToRgb, rgbToLab, labDist, resizeImage, smoothImage, distanceTransform,
} from './raster.js';
import { ringsToShapes, simplifyRing, densifyRing, smoothRing, shapeArea, ringBounds, transformShape, dist, pointInShape } from './geom.js';
import { stitchShape, limitStitchLength, dropTiny, lockAt, DEFAULTS, runLine } from './stitch.js';
import { STITCH, JUMP, TRIM, COLOR, END } from './formats.js';
import { outlineBand, insetShapes } from './library.js';

let uid = 1;
export const newId = () => 'o' + uid++ + '_' + Math.random().toString(36).slice(2, 6);

/**
 * Digitize an image into embroidery objects.
 * opts: { widthMm, colors (max), removeBackground, detail (0..1), smooth, mode: 'logo'|'photo' }
 */
export function digitizeImage(img, opts = {}) {
  const o = {
    widthMm: 80, colors: 6, removeBackground: true, detail: 0.5, smooth: true, minAreaMm2: null,
    maxPixels: 640, ...opts,
  };
  // working resolution: about 0.12-0.25 mm/pixel
  const aspect = img.height / img.width;
  const heightMm = o.widthMm * aspect;
  const targetPx = Math.min(o.maxPixels, Math.max(160, Math.round(o.widthMm / (0.25 - 0.13 * o.detail))));
  let w = targetPx, h = Math.max(8, Math.round(targetPx * aspect));
  if (h > o.maxPixels) { h = o.maxPixels; w = Math.max(8, Math.round(h / aspect)); }
  let work = resizeImage(img, w, h);
  if (o.smooth) work = smoothImage(work, o.mode === 'photo' ? 2 : 1, o.mode === 'photo' ? 14 : 22);
  const mmPerPx = o.widthMm / w;
  const { palette, labels: rawLabels } = quantize(work, Math.min(15, o.colors + (o.removeBackground ? 1 : 0)));
  // drop tiny colour clusters (anti-aliased edges) and merge near-identical ones
  const counts = new Map();
  let opaqueCount = 0;
  for (let i = 0; i < rawLabels.length; i++) if (rawLabels[i] >= 0) { counts.set(rawLabels[i], (counts.get(rawLabels[i]) || 0) + 1); opaqueCount++; }
  const remap = new Map();
  const keepL = [...counts.keys()].filter((l) => counts.get(l) / Math.max(1, opaqueCount) >= (o.mode === 'photo' ? 0.002 : 0.006));
  const sortedKeep = keepL.sort((a, b) => counts.get(b) - counts.get(a));
  const finalKeep = [];
  for (const l of sortedKeep) {
    const twin = finalKeep.find((k) => labDist(palette[k], palette[l]) < (o.mode === 'photo' ? 4 : 7));
    if (twin !== undefined) remap.set(l, twin); else finalKeep.push(l);
  }
  for (const l of counts.keys()) {
    if (finalKeep.includes(l) || remap.has(l)) continue;
    let best = finalKeep[0], bd = Infinity;
    for (const k of finalKeep) { const d = labDist(palette[k], palette[l]); if (d < bd) { bd = d; best = k; } }
    remap.set(l, best);
  }
  for (let i = 0; i < rawLabels.length; i++) if (remap.has(rawLabels[i])) rawLabels[i] = remap.get(rawLabels[i]);
  let labels = modeFilter(rawLabels, w, h, o.mode === 'photo' ? 1 : 2);
  const minArea = o.minAreaMm2 ?? (o.mode === 'photo' ? 1.2 : 0.6) * (1.2 - o.detail);
  const minPx = Math.max(3, Math.round(minArea / (mmPerPx * mmPerPx)));
  labels = removeSmallRegions(labels, w, h, minPx);
  // thin slivers along colour borders (anti-aliasing, JPEG noise) are merged away;
  // in logo mode only very thin ones so real hairlines survive
  const thinMm = o.mode === 'photo' ? 0.45 : 0.22;
  labels = mergeThinRegions(labels, w, h, Math.max(1, thinMm / mmPerPx));
  labels = removeSmallRegions(labels, w, h, minPx);

  // merge palette entries that ended up visually identical
  const used = new Map();
  for (let i = 0; i < labels.length; i++) if (labels[i] >= 0) used.set(labels[i], (used.get(labels[i]) || 0) + 1);

  let bgLabel = null;
  if (o.removeBackground) {
    const b = borderLabel(labels, w, h);
    if (b.label !== null && b.label >= 0 && b.share > 0.45) bgLabel = b.label;
  }

  // colour order: biggest areas first (background-ish shapes), small details last,
  // darkest colours (outlines/text) at the end.
  const order = [...used.keys()].filter((l) => l !== bgLabel).sort((a, b) => {
    const La = palette[a][0], Lb = palette[b][0];
    const sa = used.get(a), sb = used.get(b);
    return sb * (0.5 + La / 200) - sa * (0.5 + Lb / 200);
  });

  const objects = [];
  for (const l of order) {
    const mask = new Uint8Array(w * h);
    for (let i = 0; i < labels.length; i++) mask[i] = labels[i] === l ? 1 : 0;
    const dt = distanceTransform(mask, w, h);
    const rings = traceMask(mask, w, h).map((r) => {
      let pts = densifyRing(r, 1);
      pts = smoothRing(pts, o.mode === 'photo' ? 1 : 2);
      pts = smoothRing(pts, 1, -2);
      pts = pts.map((p) => ({ x: p.x * mmPerPx, y: p.y * mmPerPx }));
      pts = simplifyRing(pts, mmPerPx * 0.3);
      return pts;
    });
    const shapes = ringsToShapes(rings).filter((s) => shapeArea(s) > minArea * 0.5);
    if (!shapes.length) continue;
    const [R, G, B] = labToRgb(...palette[l]);
    // width per shape from distance transform (max inner distance * 2)
    const { comp } = components(Uint8Array.from(mask), w, h);
    const compMax = new Map();
    for (let i = 0; i < mask.length; i++) {
      if (!mask[i]) continue;
      const c = comp[i], cur = compMax.get(c);
      if (!cur || dt[i] > cur.d) compMax.set(c, { d: dt[i], i });
    }
    const peaks = [...compMax.values()].map((v) => ({ d: v.d, x: ((v.i % w) + 0.5) * mmPerPx, y: (Math.floor(v.i / w) + 0.5) * mmPerPx }));
    for (const s of shapes) {
      const b = ringBounds(s.outer);
      let best = 0;
      for (const pk of peaks) {
        if (pk.x < b.minX || pk.x > b.maxX || pk.y < b.minY || pk.y > b.maxY) continue;
        if (pk.d > best && pointInShape(pk.x, pk.y, s)) best = pk.d;
      }
      s.width = best ? best * 2 * mmPerPx : null;
    }
    objects.push({
      id: newId(), type: 'region', name: 'אזור', color: rgbToHex(R, G, B), shapes,
      stitch: { kind: 'auto' }, visible: true,
    });
  }
  return { objects, widthMm: o.widthMm, heightMm, background: bgLabel !== null ? rgbToHex(...labToRgb(...palette[bgLabel])) : null };
}

// Merge colours that are closer than `threshold` (Lab ΔE) – used for the 12-needle limit.
export function mergeSimilarColors(objects, maxColors) {
  const colors = [...new Set(objects.map((o) => o.color.toLowerCase()))];
  if (colors.length <= maxColors) return objects;
  const labs = new Map(colors.map((c) => [c, rgbToLab(...hexToRgb(c))]));
  let groups = colors.map((c) => [c]);
  while (groups.length > maxColors) {
    let bi = 0, bj = 1, bd = Infinity;
    for (let i = 0; i < groups.length; i++) for (let j = i + 1; j < groups.length; j++) {
      const d = labDist(labs.get(groups[i][0]), labs.get(groups[j][0]));
      if (d < bd) { bd = d; bi = i; bj = j; }
    }
    groups[bi] = groups[bi].concat(groups[bj]);
    groups.splice(bj, 1);
  }
  const map = new Map();
  for (const g of groups) for (const c of g) map.set(c, g[0]);
  return objects.map((o) => ({ ...o, color: map.get(o.color.toLowerCase()) || o.color }));
}

export function designBounds(objects) {
  let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity;
  for (const o of objects) {
    if (o.visible === false) continue;
    if (o.shapes) for (const s of o.shapes) {
      const b = ringBounds(s.outer);
      minX = Math.min(minX, b.minX); minY = Math.min(minY, b.minY); maxX = Math.max(maxX, b.maxX); maxY = Math.max(maxY, b.maxY);
    }
    if (o.points) for (const p of o.points) {
      minX = Math.min(minX, p.x); minY = Math.min(minY, p.y); maxX = Math.max(maxX, p.x); maxY = Math.max(maxY, p.y);
    }
  }
  if (minX === Infinity) return { minX: 0, minY: 0, maxX: 0, maxY: 0, w: 0, h: 0 };
  return { minX, minY, maxX, maxY, w: maxX - minX, h: maxY - minY };
}

// Scale / rotate / mirror all objects around the design centre.
export function transformObjects(objects, { scale = 1, sx, sy, rotate = 0, flipX = false, flipY = false } = {}) {
  const b = designBounds(objects);
  const cx = (b.minX + b.maxX) / 2, cy = (b.minY + b.maxY) / 2;
  const kx = (sx ?? scale) * (flipX ? -1 : 1), ky = (sy ?? scale) * (flipY ? -1 : 1);
  const c = Math.cos(rotate), s = Math.sin(rotate);
  // M = R * S, then translate back to centre
  const a = c * kx, bb = s * kx, cc = -s * ky, d = c * ky;
  const m = [a, bb, cc, d, cx - a * cx - cc * cy, cy - bb * cx - d * cy];
  return objects.map((o) => {
    const n = { ...o };
    if (o.shapes) n.shapes = o.shapes.map((sh) => {
      const t = transformShape(sh, m);
      if (sh.width) t.width = sh.width * Math.sqrt(Math.abs(kx * ky));
      return t;
    });
    if (o.points) n.points = o.points.map((p) => ({ ...p, x: m[0] * p.x + m[2] * p.y + m[4], y: m[1] * p.x + m[3] * p.y + m[5] }));
    if (o.stitch && o.stitch.angle != null) n.stitch = { ...o.stitch, angle: o.stitch.angle + rotate };
    return n;
  });
}

export function centerObjects(objects) {
  const b = designBounds(objects);
  const cx = (b.minX + b.maxX) / 2, cy = (b.minY + b.maxY) / 2;
  const m = [1, 0, 0, 1, -cx, -cy];
  return objects.map((o) => {
    const n = { ...o };
    if (o.shapes) n.shapes = o.shapes.map((sh) => { const t = transformShape(sh, m); t.width = sh.width; return t; });
    if (o.points) n.points = o.points.map((p) => ({ ...p, x: p.x - cx, y: p.y - cy }));
    return n;
  });
}

// Fabric presets – density / underlay / pull compensation.
export const FABRICS = {
  cotton: { label: 'כותנה / אריג רגיל', fillSpacing: 0.42, pullComp: 0.2, underlay: true },
  tshirt: { label: 'טריקו / חולצת טי', fillSpacing: 0.45, pullComp: 0.3, underlay: true },
  pique: { label: 'פיקה / חולצת פולו', fillSpacing: 0.42, pullComp: 0.3, underlay: true },
  denim: { label: 'ג׳ינס / בד עבה', fillSpacing: 0.4, pullComp: 0.15, underlay: true },
  towel: { label: 'מגבת / בד שעיר', fillSpacing: 0.38, pullComp: 0.25, underlay: true },
  cap: { label: 'כובע', fillSpacing: 0.4, pullComp: 0.25, underlay: true },
  fleece: { label: 'פליז / צמר', fillSpacing: 0.4, pullComp: 0.3, underlay: true },
};

/**
 * Compile objects into colour blocks of stitches (mm).
 * Returns { blocks: [{color, objectIds, points:[{x,y,j,t}]}] }
 */
export function compileDesign(objects, settings = {}) {
  const fab = FABRICS[settings.fabric || 'cotton'] || FABRICS.cotton;
  const base = { ...DEFAULTS, fillSpacing: fab.fillSpacing, pullComp: fab.pullComp, underlay: fab.underlay };
  if (settings.densityFactor) base.fillSpacing = base.fillSpacing / settings.densityFactor;
  if (settings.pullComp != null) base.pullComp = settings.pullComp;
  if (settings.underlay != null) base.underlay = settings.underlay;
  const blocks = [];
  let pos = null;
  // appliqué objects become three machine steps with stops between them
  const expanded = [];
  for (const obj of objects) {
    if (obj.visible === false) continue;
    if (obj.applique && obj.shapes && obj.shapes.length) {
      const bw = obj.applique.width || 3;
      expanded.push({ ...obj, id: obj.id + '_place', name: 'אפליקציה 1: קו מיקום – הניחו את הבד', applique: null, forceNewBlock: true, stitch: { kind: 'run', bean: false } });
      const tack = insetShapes(obj.shapes, Math.min(0.8, bw * 0.25));
      expanded.push({ ...obj, id: obj.id + '_tack', name: 'אפליקציה 2: תפר הצמדה – אחר כך גזרו את עודפי הבד', shapes: tack.length ? tack : obj.shapes, applique: null, forceNewBlock: true, stitch: { kind: 'run', bean: true } });
      expanded.push({ ...obj, id: obj.id + '_cover', name: 'אפליקציה 3: כיסוי סאטן', shapes: outlineBand(obj.shapes, bw, 0.62), applique: null, forceNewBlock: true, stitch: { kind: 'satin' } });
    } else expanded.push(obj);
  }
  for (const obj of expanded) {
    if (obj.visible === false) continue;
    const o = { ...base, ...(obj.stitch || {}) };
    if (obj.stitch?.density) o.fillSpacing = base.fillSpacing / obj.stitch.density;
    let pts = [];
    if (obj.points) {
      // pre-computed stitches (photo mode, imported files)
      pts = obj.points.map((p) => ({ ...p }));
    } else if (obj.shapes) {
      // order shapes greedily by distance
      const left = obj.shapes.slice();
      while (left.length) {
        let bi = 0, bd = Infinity;
        if (pos) for (let i = 0; i < left.length; i++) {
          const b = ringBounds(left[i].outer);
          const d = Math.hypot((b.minX + b.maxX) / 2 - pos.x, (b.minY + b.maxY) / 2 - pos.y);
          if (d < bd) { bd = d; bi = i; }
        }
        const sh = left.splice(bi, 1)[0];
        const sp = stitchShape(sh, { ...o, width: sh.width ?? undefined }, pos);
        if (!sp.length) continue;
        if (pts.length || pos) sp[0] = { ...sp[0], j: true, t: pos ? dist(pos, sp[0]) > o.trimDistance : false };
        if (pts.length && pos && dist(pos, sp[0]) <= 1.2) sp[0] = { x: sp[0].x, y: sp[0].y };
        pts.push(...sp);
        pos = pts[pts.length - 1];
      }
    }
    if (!pts.length) continue;
    if (!obj.imported) pts = addLocks(dropTiny(pts, base.minStitch));
    pos = pts[pts.length - 1];
    const last = blocks[blocks.length - 1];
    if (last && !obj.forceNewBlock && !last.forced && last.color.toLowerCase() === obj.color.toLowerCase()) {
      if (pts[0] && last.points.length) pts[0] = { ...pts[0], j: true, t: dist(last.points[last.points.length - 1], pts[0]) > o.trimDistance };
      last.points.push(...pts);
      last.objectIds.push(obj.id);
    } else {
      pts[0] = { ...pts[0], j: true, t: false };
      blocks.push({ color: obj.color, objectIds: [obj.id], points: pts, name: obj.name, forced: !!obj.forceNewBlock });
    }
  }
  // post-process each block: locks, length limits, tiny stitch removal
  for (const b of blocks) {
    b.points = limitStitchLength(b.points, 7);
  }
  return { blocks };
}

function addLocks(pts) {
  const out = [];
  for (let i = 0; i < pts.length; i++) {
    const p = pts[i];
    const startsSection = i === 0 || p.j;
    const endsSection = i === pts.length - 1 || (pts[i + 1] && pts[i + 1].j && pts[i + 1].t);
    if (startsSection) {
      out.push(p);
      const nxt = pts[i + 1];
      if (nxt && !nxt.j) out.push(...lockAt(p, nxt));
      if (endsSection && i !== pts.length - 1) out.push(...lockAt(p, pts[i - 1] || null));
      continue;
    }
    out.push(p);
    if (endsSection) out.push(...lockAt(p, pts[i - 1]));
  }
  return out;
}

/** Convert compiled blocks to a machine pattern (0.1mm units, centred). */
export function toPattern(compiled, { name = 'design' } = {}) {
  const pts = compiled.blocks.flatMap((b) => b.points);
  let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity;
  for (const p of pts) { minX = Math.min(minX, p.x); minY = Math.min(minY, p.y); maxX = Math.max(maxX, p.x); maxY = Math.max(maxY, p.y); }
  const cx = (minX + maxX) / 2 || 0, cy = (minY + maxY) / 2 || 0;
  const st = [];
  const colors = [];
  compiled.blocks.forEach((b, bi) => {
    if (bi > 0) {
      st.push([st.length ? st[st.length - 1][0] : 0, st.length ? st[st.length - 1][1] : 0, TRIM]);
      st.push([st[st.length - 1][0], st[st.length - 1][1], COLOR]);
    }
    colors.push(b.color);
    b.points.forEach((p, i) => {
      const x = (p.x - cx) * 10, y = (p.y - cy) * 10;
      if (p.j) {
        if (p.t && i > 0) st.push([st.length ? st[st.length - 1][0] : 0, st.length ? st[st.length - 1][1] : 0, TRIM]);
        st.push([x, y, JUMP]);
        st.push([x, y, STITCH]);
      } else st.push([x, y, STITCH]);
    });
  });
  st.push([st.length ? st[st.length - 1][0] : 0, st.length ? st[st.length - 1][1] : 0, END]);
  return { name, stitches: st, colors };
}

/** Turn an imported machine pattern back into editable stitch objects. */
export function patternToObjects(pattern, palette) {
  const objects = [];
  let pts = [];
  let ci = 0;
  let pendingJump = false, pendingTrim = false;
  const flush = () => {
    if (pts.length) objects.push({ id: newId(), type: 'stitches', forceNewBlock: true, name: 'צבע ' + (ci + 1), color: (pattern.colors?.[ci]) || palette[ci % palette.length], points: pts, visible: true });
    pts = [];
  };
  for (const [x, y, c] of pattern.stitches) {
    if (c === COLOR) { flush(); ci++; pendingJump = true; continue; }
    if (c === TRIM) { pendingTrim = true; continue; }
    if (c === JUMP) { pendingJump = true; continue; }
    if (c === STITCH) {
      const p = { x: x / 10, y: y / 10 };
      if (pendingJump || !pts.length) { p.j = true; p.t = pendingTrim; }
      pendingJump = false; pendingTrim = false;
      pts.push(p);
    }
  }
  flush();
  return objects;
}

export function designStats(compiled, spm = 750) {
  let stitches = 0, jumps = 0, trims = 0, longest = 0, threadMm = 0;
  for (const b of compiled.blocks) {
    for (let i = 0; i < b.points.length; i++) {
      const p = b.points[i];
      if (p.j) { jumps++; if (p.t) trims++; continue; }
      stitches++;
      if (i > 0) { const d = dist(p, b.points[i - 1]); longest = Math.max(longest, d); threadMm += d; }
    }
  }
  const colorChanges = Math.max(0, compiled.blocks.length - 1);
  // time: stitches at spm + ~6s per trim + ~8s per colour change
  const minutes = stitches / spm + (trims * 6 + colorChanges * 8) / 60;
  return { stitches, jumps, trims, colorChanges, colors: compiled.blocks.length, longest, minutes, threadM: threadMm / 1000 };
}

export { runLine };
