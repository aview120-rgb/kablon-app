// Canvas preview of the embroidery: realistic thread look, hoop, simulation.
function shade(hex, f) {
  const h = hex.replace('#', '');
  let r = parseInt(h.slice(0, 2), 16), g = parseInt(h.slice(2, 4), 16), b = parseInt(h.slice(4, 6), 16);
  if (f > 0) { r += (255 - r) * f; g += (255 - g) * f; b += (255 - b) * f; }
  else { r *= 1 + f; g *= 1 + f; b *= 1 + f; }
  return `rgb(${r | 0},${g | 0},${b | 0})`;
}

export class Preview {
  constructor(canvas) {
    this.c = canvas;
    this.ctx = canvas.getContext('2d');
    this.view = { scale: 3, ox: 0, oy: 0 };
    this.compiled = null;
    this.hoop = null;
    this.fabric = '#f4f1ea';
    this.realistic = true;
    this.showJumps = false;
    this.limit = Infinity; // simulation: number of stitches to show
    this.highlight = null; // block index to highlight
    this._setupGestures();
    this._raf = 0;
  }

  setData(compiled, hoop) { this.compiled = compiled; this.hoop = hoop; this.total = compiled ? compiled.blocks.reduce((s, b) => s + b.flags.length, 0) : 0; this.request(); }

  fit() {
    const W = this.c.clientWidth, H = this.c.clientHeight;
    const hw = this.hoop ? this.hoop.w + 20 : 120, hh = this.hoop ? this.hoop.h + 20 : 120;
    this.view.scale = Math.min(W / hw, H / hh);
    this.view.ox = 0; this.view.oy = 0;
    this.request();
  }

  request() { if (!this._raf) this._raf = requestAnimationFrame(() => { this._raf = 0; this.draw(); }); }

  resize() {
    const dpr = Math.min(2.5, window.devicePixelRatio || 1);
    const W = this.c.clientWidth, H = this.c.clientHeight;
    if (this.c.width !== Math.round(W * dpr) || this.c.height !== Math.round(H * dpr)) {
      this.c.width = Math.round(W * dpr); this.c.height = Math.round(H * dpr);
    }
    this.dpr = dpr;
  }

  draw() {
    this.resize();
    const { ctx } = this;
    const W = this.c.clientWidth, H = this.c.clientHeight;
    ctx.setTransform(this.dpr, 0, 0, this.dpr, 0, 0);
    ctx.fillStyle = '#e9e6df';
    ctx.fillRect(0, 0, W, H);
    const s = this.view.scale;
    ctx.translate(W / 2 + this.view.ox, H / 2 + this.view.oy);
    // fabric inside hoop
    if (this.hoop) {
      ctx.save();
      ctx.beginPath();
      if (this.hoop.shape === 'round') ctx.arc(0, 0, (this.hoop.w / 2) * s, 0, Math.PI * 2);
      else roundRect(ctx, (-this.hoop.w / 2) * s, (-this.hoop.h / 2) * s, this.hoop.w * s, this.hoop.h * s, 10 * s);
      ctx.fillStyle = this.fabric;
      ctx.fill();
      ctx.lineWidth = Math.max(3, 4 * s);
      ctx.strokeStyle = '#a07c52';
      ctx.stroke();
      // centre cross
      ctx.strokeStyle = 'rgba(0,0,0,0.12)';
      ctx.lineWidth = 1;
      ctx.beginPath();
      ctx.moveTo(-8 * s, 0); ctx.lineTo(8 * s, 0); ctx.moveTo(0, -8 * s); ctx.lineTo(0, 8 * s);
      ctx.stroke();
      ctx.restore();
    }
    if (!this.compiled) return;
    let remaining = this.limit;
    const lw = Math.max(0.6, 0.38 * s);
    ctx.lineCap = 'round';
    ctx.lineJoin = 'round';
    let lastPos = null;
    this.compiled.blocks.forEach((b, bi) => {
      if (remaining <= 0) return;
      const n = Math.min(b.flags.length, remaining);
      remaining -= n;
      const dim = this.highlight != null && this.highlight !== bi;
      const passes = this.realistic && s > 1.2 ? [[lw * 1.25, shade(b.color, -0.35), 0], [lw, b.color, 0], [lw * 0.35, shade(b.color, 0.45), -lw * 0.18]] : [[lw, b.color, 0]];
      ctx.globalAlpha = dim ? 0.18 : 1;
      // draw in sewing order, in short chunks, so later stitches cover earlier ones
      // (underlay stays under the satin, like on the real fabric)
      const CH = 24;
      for (let c0 = 0; c0 < n; c0 += CH) {
        const c1 = Math.min(n, c0 + CH + 1);
        for (const [width, col, off] of passes) {
          ctx.beginPath();
          ctx.strokeStyle = col;
          ctx.lineWidth = width;
          let pen = false;
          for (let i = c0; i < c1; i++) {
            const x = b.xy[i * 2] * s + off, y = b.xy[i * 2 + 1] * s + off;
            if (b.flags[i] & 1 || !pen) { ctx.moveTo(x, y); pen = true; }
            else ctx.lineTo(x, y);
          }
          ctx.stroke();
        }
      }
      if (this.showJumps) {
        ctx.beginPath();
        ctx.setLineDash([4, 4]);
        ctx.strokeStyle = 'rgba(200,0,0,0.6)';
        ctx.lineWidth = 1;
        for (let i = 1; i < n; i++) if (b.flags[i] & 1) { ctx.moveTo(b.xy[i * 2 - 2] * s, b.xy[i * 2 - 1] * s); ctx.lineTo(b.xy[i * 2] * s, b.xy[i * 2 + 1] * s); }
        ctx.stroke();
        ctx.setLineDash([]);
      }
      if (n > 0) lastPos = [b.xy[(n - 1) * 2] * s, b.xy[(n - 1) * 2 + 1] * s, b.color];
    });
    ctx.globalAlpha = 1;
    if (this.selection && this.selection.rings) {
      const off = this.selection.offset || { x: 0, y: 0 };
      ctx.save();
      ctx.setLineDash([6, 4]);
      ctx.lineWidth = 2;
      for (const [col, w] of [['#ffffff', 4], ['#e0115f', 2]]) {
        ctx.strokeStyle = col; ctx.lineWidth = w;
        ctx.beginPath();
        for (const r of this.selection.rings) {
          r.forEach((p, i) => { const x = (p.x + off.x) * s, y = (p.y + off.y) * s; if (i) ctx.lineTo(x, y); else ctx.moveTo(x, y); });
          ctx.closePath();
        }
        ctx.stroke();
      }
      ctx.restore();
    }
    if (this.limit < this.total && lastPos) {
      // needle marker
      ctx.beginPath();
      ctx.arc(lastPos[0], lastPos[1], 6, 0, Math.PI * 2);
      ctx.fillStyle = 'rgba(220,30,60,0.85)';
      ctx.fill();
      ctx.strokeStyle = '#fff'; ctx.lineWidth = 2; ctx.stroke();
    }
  }

  toMm(clientX, clientY) {
    const r = this.c.getBoundingClientRect();
    return { x: (clientX - r.left - r.width / 2 - this.view.ox) / this.view.scale, y: (clientY - r.top - r.height / 2 - this.view.oy) / this.view.scale };
  }

  _setupGestures() {
    const c = this.c;
    const pts = new Map();
    let last = null;
    let down = null;      // for tap detection
    let dragging = false; // moving a selected object
    c.addEventListener('pointerdown', (e) => {
      c.setPointerCapture(e.pointerId);
      pts.set(e.pointerId, [e.clientX, e.clientY]);
      last = null;
      dragging = false;
      if (pts.size === 1) {
        down = { x: e.clientX, y: e.clientY, t: Date.now() };
        const mm = this.toMm(e.clientX, e.clientY);
        if (this.onDragStart && this.onDragStart(mm)) { dragging = true; down.mm = mm; }
      } else down = null;
    });
    c.addEventListener('pointermove', (e) => {
      if (!pts.has(e.pointerId)) return;
      pts.set(e.pointerId, [e.clientX, e.clientY]);
      const arr = [...pts.values()];
      if (arr.length === 1) {
        const [x, y] = arr[0];
        if (dragging && down) {
          const mm = this.toMm(x, y);
          this.onDrag?.(mm.x - down.mm.x, mm.y - down.mm.y);
          this.request();
          return;
        }
        if (last && last.n === 1) { this.view.ox += x - last.x; this.view.oy += y - last.y; this.request(); }
        last = { n: 1, x, y };
      } else if (arr.length >= 2) {
        dragging = false;
        const [a, b] = arr;
        const d = Math.hypot(a[0] - b[0], a[1] - b[1]);
        const mx = (a[0] + b[0]) / 2, my = (a[1] + b[1]) / 2;
        if (last && last.n === 2) {
          const k = d / last.d;
          this._zoomAt(mx, my, k);
          this.view.ox += mx - last.x; this.view.oy += my - last.y;
        }
        last = { n: 2, d, x: mx, y: my };
      }
    });
    const up = (e) => {
      if (down && pts.size === 1 && e.type === 'pointerup') {
        const moved = Math.hypot(e.clientX - down.x, e.clientY - down.y);
        if (dragging) {
          const mm = this.toMm(e.clientX, e.clientY);
          if (moved > 4) this.onDragEnd?.(mm.x - down.mm.x, mm.y - down.mm.y);
          else { this.onDragEnd?.(0, 0); this.onTap?.(this.toMm(e.clientX, e.clientY)); }
        } else if (moved < 8 && Date.now() - down.t < 500) this.onTap?.(this.toMm(e.clientX, e.clientY));
      }
      pts.delete(e.pointerId); last = null;
      if (!pts.size) { down = null; dragging = false; }
    };
    c.addEventListener('pointerup', up);
    c.addEventListener('pointercancel', up);
    c.addEventListener('wheel', (e) => { e.preventDefault(); this._zoomAt(e.clientX, e.clientY, e.deltaY < 0 ? 1.15 : 1 / 1.15); }, { passive: false });
  }

  _zoomAt(cx, cy, k) {
    const r = this.c.getBoundingClientRect();
    const px = cx - r.left - r.width / 2 - this.view.ox, py = cy - r.top - r.height / 2 - this.view.oy;
    const ns = Math.max(0.3, Math.min(80, this.view.scale * k));
    const kk = ns / this.view.scale;
    this.view.ox -= px * (kk - 1); this.view.oy -= py * (kk - 1);
    this.view.scale = ns;
    this.request();
  }
  zoom(k) { const r = this.c.getBoundingClientRect(); this._zoomAt(r.left + r.width / 2, r.top + r.height / 2, k); }
}

function roundRect(ctx, x, y, w, h, r) {
  ctx.moveTo(x + r, y);
  ctx.arcTo(x + w, y, x + w, y + h, r);
  ctx.arcTo(x + w, y + h, x, y + h, r);
  ctx.arcTo(x, y + h, x, y, r);
  ctx.arcTo(x, y, x + w, y, r);
  ctx.closePath();
}

/** Draw a small thumbnail of compiled data into a new canvas. */
export function thumbnail(compiled, size = 200, bg = '#ffffff', onlyBlock = null) {
  const c = document.createElement('canvas');
  c.width = size; c.height = size;
  const ctx = c.getContext('2d');
  ctx.fillStyle = bg; ctx.fillRect(0, 0, size, size);
  let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity;
  for (const b of compiled.blocks) for (let i = 0; i < b.xy.length; i += 2) {
    minX = Math.min(minX, b.xy[i]); maxX = Math.max(maxX, b.xy[i]); minY = Math.min(minY, b.xy[i + 1]); maxY = Math.max(maxY, b.xy[i + 1]);
  }
  if (minX === Infinity) return c;
  const s = (size * 0.9) / Math.max(1, maxX - minX, maxY - minY);
  const ox = size / 2 - ((minX + maxX) / 2) * s, oy = size / 2 - ((minY + maxY) / 2) * s;
  ctx.lineCap = 'round';
  compiled.blocks.forEach((b, bi) => {
    ctx.globalAlpha = onlyBlock == null || onlyBlock === bi ? 1 : 0.12;
    ctx.strokeStyle = onlyBlock == null || onlyBlock === bi ? b.color : '#999';
    ctx.lineWidth = Math.max(0.7, 0.4 * s);
    ctx.beginPath();
    for (let i = 0; i < b.flags.length; i++) {
      const x = b.xy[i * 2] * s + ox, y = b.xy[i * 2 + 1] * s + oy;
      if (i === 0 || b.flags[i] & 1) ctx.moveTo(x, y); else ctx.lineTo(x, y);
    }
    ctx.stroke();
  });
  ctx.globalAlpha = 1;
  return c;
}
