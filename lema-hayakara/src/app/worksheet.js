// Needle sheet: which needle sews each step, in which colour – printable PNG.
import { thumbnail } from './render.js';
import { colorName, textColorOn } from './colornames.js';
import { compiledStats, compiledBounds } from './warnings.js';

export function renderWorksheet(compiled, { name, needles, hoopLabel, fabricLabel, threadNames = {} }) {
  const steps = compiled.blocks;
  const W = 1240;
  const rowH = 118;
  const H = 520 + steps.length * rowH + 140;
  const c = document.createElement('canvas');
  c.width = W; c.height = H;
  const ctx = c.getContext('2d');
  ctx.direction = 'rtl';
  ctx.fillStyle = '#fff'; ctx.fillRect(0, 0, W, H);
  const st = compiledStats(compiled);
  const b = compiledBounds(compiled);
  ctx.fillStyle = '#7a2e5c';
  ctx.fillRect(0, 0, W, 110);
  ctx.fillStyle = '#fff';
  ctx.textAlign = 'right';
  ctx.font = 'bold 46px Rubik, Arial, sans-serif';
  ctx.fillText('דף הוראות לרקמה – ' + (name || 'עיצוב'), W - 40, 72);
  // overall preview
  const big = thumbnail(compiled, 360, '#f7f5f0');
  ctx.drawImage(big, 40, 140);
  ctx.strokeStyle = '#ccc'; ctx.strokeRect(40, 140, 360, 360);
  ctx.fillStyle = '#222';
  ctx.font = '30px Rubik, Arial, sans-serif';
  const lines = [
    `גודל: רוחב ${(b.w / 10).toFixed(1)} ס״מ, גובה ${(b.h / 10).toFixed(1)} ס״מ`,
    `מספר תפרים: ${st.stitches.toLocaleString('he-IL')}`,
    `שלבים (החלפות צבע): ${steps.length} (${st.colorChanges})`,
    `חיתוכי חוט: ${st.trims}`,
    `זמן רקמה משוער: ${Math.max(1, Math.round(st.minutes))} דקות`,
    `חישוק: ${hoopLabel || '-'}`,
    `בד: ${fabricLabel || '-'}`,
    `אורך חוט משוער: ${st.threadM.toFixed(1)} מטר`,
  ];
  lines.forEach((t, i) => ctx.fillText(t, W - 40, 170 + i * 42));
  ctx.font = 'bold 32px Rubik, Arial, sans-serif';
  ctx.fillText('סדר הרקמה – כוונו במסך המכונה את המחט של כל שלב:', W - 40, 545);
  steps.forEach((blk, i) => {
    const y = 570 + i * rowH;
    ctx.fillStyle = i % 2 ? '#faf7fb' : '#fff';
    ctx.fillRect(30, y, W - 60, rowH - 8);
    const needle = needles[blk.color.toLowerCase()] ?? i + 1;
    // step number
    ctx.fillStyle = '#7a2e5c';
    ctx.font = 'bold 40px Rubik, Arial, sans-serif';
    ctx.textAlign = 'right';
    ctx.fillText(`שלב ${i + 1}`, W - 50, y + 64);
    // needle badge
    ctx.fillStyle = blk.color;
    ctx.beginPath(); ctx.arc(W - 300, y + 52, 42, 0, Math.PI * 2); ctx.fill();
    ctx.strokeStyle = '#333'; ctx.lineWidth = 2; ctx.stroke();
    ctx.fillStyle = textColorOn(blk.color);
    ctx.textAlign = 'center';
    ctx.font = 'bold 38px Rubik, Arial, sans-serif';
    ctx.fillText(String(needle), W - 300, y + 66);
    ctx.textAlign = 'right';
    ctx.fillStyle = '#222';
    ctx.font = '30px Rubik, Arial, sans-serif';
    const tn = threadNames[blk.color.toLowerCase()];
    ctx.fillText(`מחט ${needle} – ${colorName(blk.color)}${tn ? ' (' + tn + ')' : ''}`, W - 370, y + 46);
    ctx.font = '24px Rubik, Arial, sans-serif';
    ctx.fillStyle = '#555';
    const n = blk.flags.reduce((s, f) => s + (f & 1 ? 0 : 1), 0);
    ctx.fillText(`${n.toLocaleString('he-IL')} תפרים`, W - 370, y + 84);
    ctx.direction = 'ltr'; ctx.textAlign = 'left';
    ctx.fillText(blk.color.toUpperCase(), 180, y + 84);
    ctx.direction = 'rtl'; ctx.textAlign = 'right';
    // step thumbnail
    const th = thumbnail(compiled, 100, '#fff', i);
    ctx.drawImage(th, 50, y + 4);
  });
  ctx.fillStyle = '#888';
  ctx.font = '22px Rubik, Arial, sans-serif';
  ctx.textAlign = 'right';
  ctx.fillText('נוצר באפליקציה "לאמא היקרה" ❤', W - 40, H - 40);
  return c;
}
