// Checks a design and returns friendly warnings with one-tap fixes.
import { fitsHoop, HOOP_MARGIN } from './hoops.js';

export const NEEDLES = 12;

export function compiledBounds(compiled) {
  let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity;
  for (const b of compiled.blocks) {
    for (let i = 0; i < b.xy.length; i += 2) {
      const x = b.xy[i], y = b.xy[i + 1];
      if (x < minX) minX = x; if (x > maxX) maxX = x;
      if (y < minY) minY = y; if (y > maxY) maxY = y;
    }
  }
  if (minX === Infinity) return { minX: 0, minY: 0, maxX: 0, maxY: 0, w: 0, h: 0 };
  return { minX, minY, maxX, maxY, w: maxX - minX, h: maxY - minY };
}

export function compiledStats(compiled, spm = 750) {
  let stitches = 0, jumps = 0, trims = 0, threadMm = 0, longest = 0;
  for (const b of compiled.blocks) {
    for (let i = 0; i < b.flags.length; i++) {
      const f = b.flags[i];
      if (f & 1) { jumps++; if (f & 2) trims++; continue; }
      stitches++;
      if (i > 0) {
        const d = Math.hypot(b.xy[i * 2] - b.xy[i * 2 - 2], b.xy[i * 2 + 1] - b.xy[i * 2 - 1]);
        threadMm += d; if (d > longest) longest = d;
      }
    }
  }
  const colorChanges = Math.max(0, compiled.blocks.length - 1);
  const minutes = stitches / spm + (trims * 5 + colorChanges * 6) / 60;
  return { stitches, jumps, trims, colorChanges, steps: compiled.blocks.length, threadM: threadMm / 1000, longest, minutes };
}

function* allPoints(compiled) {
  for (const b of compiled.blocks) for (let i = 0; i < b.xy.length; i += 2) yield [b.xy[i], b.xy[i + 1]];
}

/**
 * level: 'error' (must fix before sewing), 'warn' (should check), 'info'
 * fix: action id handled by the UI.
 */
export function analyze({ compiled, objects, settings, hoop, uniqueColors }) {
  const out = [];
  if (!compiled || !compiled.blocks.length) {
    out.push({ level: 'info', text: 'אין עדיין רקמה. הוסיפו תמונה או טקסט.' });
    return out;
  }
  const b = compiledBounds(compiled);
  const st = compiledStats(compiled);
  if (uniqueColors > NEEDLES) {
    out.push({ level: 'error', text: `בעיצוב יש ${uniqueColors} צבעים, ולמכונה יש ${NEEDLES} מחטים. צריך למזג צבעים כדי לא להחליף חוטים באמצע.`, fix: 'mergeColors', fixLabel: `מזג ל-${NEEDLES} צבעים` });
  }
  if (!fitsHoop(b, hoop, hoop.shape === 'round' ? allPoints(compiled) : null)) {
    out.push({ level: 'error', text: `העיצוב (רוחב ${(b.w / 10).toFixed(1)}, גובה ${(b.h / 10).toFixed(1)} ס״מ) גדול מהחישוק שנבחר (${hoop.label}). המחט עלולה לפגוע במסגרת.`, fix: 'fitHoop', fixLabel: 'הקטן כדי שיתאים לחישוק' });
  }
  if (b.w < 8 && b.h < 8) {
    out.push({ level: 'warn', text: 'העיצוב קטן מאוד (פחות מ-8 מ״מ). פרטים עלולים להיעלם ברקמה.', fix: 'enlarge', fixLabel: 'הגדל ל-3 ס״מ' });
  }
  // text objects
  for (const o of objects) {
    if (o.visible === false) continue;
    if (o.type === 'text') {
      const h = o.text?.heightMm ?? 0;
      if (h && h < 4) out.push({ level: 'error', text: `הטקסט "${short(o.text.value)}" נמוך מדי (${h.toFixed(1)} מ״מ). מתחת ל-4 מ״מ האותיות לא ייקראו.`, fix: 'textMin:' + o.id, fixLabel: 'הגדל ל-6 מ״מ' });
      else if (h && h < 6) out.push({ level: 'warn', text: `הטקסט "${short(o.text.value)}" קטן (${h.toFixed(1)} מ״מ). מומלץ לפחות 6 מ״מ, ובגופן פשוט ועבה.`, fix: 'textMin:' + o.id, fixLabel: 'הגדל ל-6 מ״מ' });
    }
    if (o.type === 'text' && o.shapes && o.shapes.length) {
      const ws = o.shapes.map((sh) => sh.width || 0).filter((x) => x > 0).sort((a, b) => a - b);
      const med = ws.length ? ws[Math.floor(ws.length / 2)] : 0;
      if (med && med < 1.1) out.push({ level: 'warn', text: `הקווים של האותיות ב"${short(o.text?.value)}" דקים מאוד (${med.toFixed(1)} מ״מ) – הם ייתפרו כקו ולא כסאטן מלא. כדאי להגדיל, לבחור עובי "עבה" או גופן עבה יותר.`, fix: 'textMin:' + o.id, fixLabel: 'הגדל' });
    }
    if (o.type === 'stitches' && o.imported && o.scaled && Math.abs(o.scaled - 1) > 0.2) {
      out.push({ level: 'warn', text: 'קובץ רקמה מוכן שונה בגודל ביותר מ-20%. הצפיפות משתנה – מומלץ לבדוק בתצוגה או לחזור לגודל המקורי.', fix: 'resetImportScale:' + o.id, fixLabel: 'חזור לגודל המקורי' });
    }
  }
  // thin details
  let thin = 0;
  for (const o of objects) if (o.shapes && o.visible !== false) for (const s of o.shapes) if (s.width != null && s.width < 0.9) thin++;
  if (thin > 0) out.push({ level: 'info', text: `${thin} פרטים דקים מאוד יירקמו כקו תפר (לא כמילוי).` });
  if ((settings.densityFactor || 1) > 1.3) out.push({ level: 'warn', text: 'הצפיפות גבוהה מאוד – הבד עלול להתכווץ או להיקרע והמחט עלולה להישבר.', fix: 'densityNormal', fixLabel: 'החזר צפיפות רגילה' });
  if ((settings.densityFactor || 1) < 0.75) out.push({ level: 'warn', text: 'הצפיפות נמוכה – הבד עלול להיראות בין התפרים.', fix: 'densityNormal', fixLabel: 'החזר צפיפות רגילה' });
  if (st.trims > 80) out.push({ level: 'warn', text: `יש הרבה חיתוכי חוט (${st.trims}). הרקמה תהיה איטית. אפשר להוריד את רמת הפרטים או לאחד צבעים.` });
  if (st.stitches > 120000) out.push({ level: 'warn', text: `העיצוב כבד מאוד (${st.stitches.toLocaleString('he-IL')} תפרים). הרקמה תיקח זמן רב והבד עלול להתקשות.` });
  if (hoop.cap) {
    if (b.h > hoop.h - 2 * HOOP_MARGIN && !out.some((w) => w.fix === 'fitHoop')) out.push({ level: 'error', text: 'העיצוב גבוה מדי לכובע.', fix: 'fitHoop', fixLabel: 'התאם לכובע' });
    out.push({ level: 'info', text: 'רקמה על כובע: מומלץ שהרקמה תתחיל מהמרכז ותתקדם החוצה, ובצפיפות מעט גבוהה יותר.' });
  }
  if (settings.fabric === 'towel') out.push({ level: 'info', text: 'במגבת מומלץ להניח מעל הבד שכבת "טופינג" מסיסה במים כדי שהתפרים לא ישקעו.' });
  if (settings.fabric === 'tshirt' || settings.fabric === 'pique') out.push({ level: 'info', text: 'בבדי סריג (טריקו/פיקה) מומלץ מייצב מסוג Cut-Away מתחת לבד.' });
  if (!out.some((w) => w.level !== 'info')) out.unshift({ level: 'ok', text: 'הכול תקין – אפשר לשמור ולרקום!' });
  return out;
}

function short(s) { s = String(s || '').replace(/\n/g, ' '); return s.length > 18 ? s.slice(0, 18) + '…' : s; }
