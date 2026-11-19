// Platform glue: saving files, sharing and updates on Android (Capacitor),
// Windows (Electron) and a plain browser fallback (used in development).
import { Capacitor } from '@capacitor/core';

export const APP_VERSION = typeof __APP_VERSION__ !== 'undefined' ? __APP_VERSION__ : '0.0.0';
export const REPO = 'aview120-rgb/kablon-app';
const native = typeof window !== 'undefined' ? window.lemaNative : null; // Electron preload

export const platform = native ? 'windows' : Capacitor.isNativePlatform() ? 'android' : 'web';

function toBase64(bytes) {
  let s = '';
  const chunk = 0x8000;
  for (let i = 0; i < bytes.length; i += chunk) s += String.fromCharCode.apply(null, bytes.subarray(i, i + chunk));
  return btoa(s);
}

export const SAVE_FOLDER = 'LeImaHayakara';

/** Save bytes to a file. Returns {ok, where, uri} */
export async function saveFile(name, bytes, mime = 'application/octet-stream') {
  if (native) {
    const r = await native.saveFile(name, bytes);
    return r ? { ok: true, where: r } : { ok: false, cancelled: true };
  }
  if (platform === 'android') {
    const { Filesystem, Directory } = await import('@capacitor/filesystem');
    const data = toBase64(bytes);
    try {
      const r = await Filesystem.writeFile({ path: `${SAVE_FOLDER}/${name}`, data, directory: Directory.Documents, recursive: true });
      return { ok: true, where: `Documents/${SAVE_FOLDER}/${name}`, uri: r.uri };
    } catch (e) {
      // fall back to app storage + share sheet
      const r = await Filesystem.writeFile({ path: name, data, directory: Directory.Cache, recursive: true });
      await shareUri(r.uri, name);
      return { ok: true, where: 'שיתוף', uri: r.uri, shared: true };
    }
  }
  // browser fallback
  const blob = new Blob([bytes], { type: mime });
  const a = document.createElement('a');
  a.href = URL.createObjectURL(blob);
  a.download = name;
  document.body.appendChild(a);
  a.click();
  setTimeout(() => { URL.revokeObjectURL(a.href); a.remove(); }, 2000);
  return { ok: true, where: 'הורדות' };
}

/** Save several files at once (one folder choice on Windows). */
export async function saveMany(files) {
  if (native && native.saveMany) {
    const dir = await native.saveMany(files.map((f) => ({ name: f.name, bytes: f.bytes })));
    return dir ? { ok: true, where: dir } : { ok: false };
  }
  let where = '';
  for (const f of files) { const r = await saveFile(f.name, f.bytes); if (!r.ok) return r; where = r.where; }
  return { ok: true, where: platform === 'android' ? `Documents/${SAVE_FOLDER}` : where };
}

async function shareUri(uri, title) {
  const { Share } = await import('@capacitor/share');
  await Share.share({ title, files: [uri], dialogTitle: 'שמירה / שליחה' });
}

/** Share a file (Android share sheet: Drive, WhatsApp, USB file manager…). */
export async function shareFile(name, bytes) {
  if (platform === 'android') {
    const { Filesystem, Directory } = await import('@capacitor/filesystem');
    const r = await Filesystem.writeFile({ path: name, data: toBase64(bytes), directory: Directory.Cache, recursive: true });
    await shareUri(r.uri, name);
    return true;
  }
  if (navigator.canShare) {
    const f = new File([bytes], name);
    if (navigator.canShare({ files: [f] })) { await navigator.share({ files: [f], title: name }); return true; }
  }
  await saveFile(name, bytes);
  return true;
}

export const canShare = () => platform === 'android' || (typeof navigator !== 'undefined' && !!navigator.canShare);

function cmpVer(a, b) {
  const pa = String(a).replace(/^v/, '').split('.').map((x) => parseInt(x, 10) || 0);
  const pb = String(b).replace(/^v/, '').split('.').map((x) => parseInt(x, 10) || 0);
  for (let i = 0; i < 3; i++) if ((pa[i] || 0) !== (pb[i] || 0)) return (pa[i] || 0) - (pb[i] || 0);
  return 0;
}

/**
 * Check GitHub releases for a newer version.
 * Returns {available, version, notes, url} or {available:false}
 */
export async function checkForUpdate() {
  if (native) return native.checkUpdates();
  const r = await fetch(`https://api.github.com/repos/${REPO}/releases/latest`, { headers: { Accept: 'application/vnd.github+json' } });
  if (!r.ok) throw new Error('לא הצלחנו לבדוק עדכונים (אין אינטרנט?)');
  const rel = await r.json();
  const version = rel.tag_name.replace(/^v/, '');
  const apk = (rel.assets || []).find((a) => a.name.endsWith('.apk'));
  return { available: cmpVer(version, APP_VERSION) > 0, version, notes: rel.body || '', url: apk ? apk.browser_download_url : rel.html_url };
}

export async function installUpdate(info) {
  if (native) return native.installUpdate();
  if (platform === 'android') {
    const { Browser } = await import('@capacitor/browser');
    // The browser downloads the new APK; opening it updates the app in place (data is kept).
    await Browser.open({ url: info.url });
    return;
  }
  window.open(info.url, '_blank');
}

export function onNativeUpdateEvent(cb) { if (native && native.onUpdateEvent) native.onUpdateEvent(cb); }
