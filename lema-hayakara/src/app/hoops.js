// Hoop presets for SWF single-head machines (approximate sewing fields, mm).
// Users should check against their own frames – a custom size is available.
export const HOOPS = [
  { id: 'r90', label: 'חישוק עגול 9 ס״מ', shape: 'round', w: 90, h: 90 },
  { id: 'r120', label: 'חישוק עגול 12 ס״מ', shape: 'round', w: 120, h: 120 },
  { id: 'r150', label: 'חישוק עגול 15 ס״מ', shape: 'round', w: 150, h: 150 },
  { id: 'r180', label: 'חישוק עגול 18 ס״מ', shape: 'round', w: 180, h: 180 },
  { id: 'r210', label: 'חישוק עגול 21 ס״מ', shape: 'round', w: 210, h: 210 },
  { id: 's240', label: 'חישוק מרובע 24×24 ס״מ', shape: 'rect', w: 240, h: 240 },
  { id: 'r300x200', label: 'חישוק מלבני 30×20 ס״מ', shape: 'rect', w: 300, h: 200 },
  { id: 'max', label: 'שדה מקסימלי של המכונה (35.6×22.9 ס״מ)', shape: 'rect', w: 356, h: 229 },
  { id: 'cap', label: 'כובע – חזית (13×6 ס״מ)', shape: 'rect', w: 130, h: 60, cap: true },
  { id: 'capwide', label: 'כובע רחב 270° (30×6.5 ס״מ)', shape: 'rect', w: 300, h: 65, cap: true },
  { id: 'custom', label: 'גודל אחר…', shape: 'rect', w: 150, h: 150, custom: true },
];
export const HOOP_MARGIN = 5; // mm safety distance from the frame

export function hoopById(id, custom) {
  const h = HOOPS.find((x) => x.id === id) || HOOPS[2];
  if (h.custom && custom) return { ...h, w: custom.w, h: custom.h };
  return h;
}

// Does a set of points (mm, centred at 0,0) fit inside the hoop?
export function fitsHoop(bounds, hoop, pointsIter) {
  const m = HOOP_MARGIN;
  if (hoop.shape === 'round') {
    const R = hoop.w / 2 - m;
    if (pointsIter) {
      for (const [x, y] of pointsIter) if (x * x + y * y > R * R) return false;
      return true;
    }
    const corners = [[bounds.minX, bounds.minY], [bounds.maxX, bounds.minY], [bounds.minX, bounds.maxY], [bounds.maxX, bounds.maxY]];
    return corners.every(([x, y]) => x * x + y * y <= R * R);
  }
  return bounds.minX >= -hoop.w / 2 + m && bounds.maxX <= hoop.w / 2 - m && bounds.minY >= -hoop.h / 2 + m && bounds.maxY <= hoop.h / 2 - m;
}
