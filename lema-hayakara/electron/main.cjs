// Windows desktop shell for "לאמא היקרה".
const { app, BrowserWindow, protocol, net, ipcMain, dialog, shell, Menu } = require('electron');
const path = require('path');
const fs = require('fs');
const { pathToFileURL } = require('url');

protocol.registerSchemesAsPrivileged([
  { scheme: 'app', privileges: { standard: true, secure: true, supportFetchAPI: true, stream: true, corsEnabled: true } },
]);

const DIST = path.join(__dirname, '..', 'dist');
let win = null;
let updater = null;
let lastUpdate = null;

function send(ev) { if (win && !win.isDestroyed()) win.webContents.send('lema:update', ev); }

function setupUpdater() {
  try {
    ({ autoUpdater: updater } = require('electron-updater'));
  } catch { return; }
  updater.autoDownload = true;
  updater.autoInstallOnAppQuit = true;
  updater.on('update-available', (i) => { lastUpdate = i; send({ type: 'available', version: i.version }); });
  updater.on('update-downloaded', (i) => { lastUpdate = i; send({ type: 'downloaded', version: i.version }); });
  updater.on('error', (e) => console.warn('update error', e && e.message));
  if (app.isPackaged) setTimeout(() => updater.checkForUpdates().catch(() => {}), 8000);
}

function createWindow() {
  win = new BrowserWindow({
    width: 1280, height: 860, minWidth: 380, minHeight: 600,
    title: 'לאמא היקרה',
    icon: path.join(__dirname, '..', 'assets', 'icon.png'),
    backgroundColor: '#f6f2f4',
    autoHideMenuBar: true,
    webPreferences: { preload: path.join(__dirname, 'preload.cjs'), contextIsolation: true, nodeIntegration: false, sandbox: true },
  });
  Menu.setApplicationMenu(null);
  win.loadURL('app://lema/index.html');
  win.webContents.setWindowOpenHandler(({ url }) => { if (/^https?:/.test(url)) shell.openExternal(url); return { action: 'deny' }; });
}

app.whenReady().then(() => {
  protocol.handle('app', async (req) => {
    const u = new URL(req.url);
    let rel = decodeURIComponent(u.pathname);
    if (rel === '/' || !rel) rel = '/index.html';
    const file = path.normalize(path.join(DIST, rel));
    if (!file.startsWith(DIST)) return new Response('forbidden', { status: 403 });
    const res = await net.fetch(pathToFileURL(file).toString());
    const headers = new Headers(res.headers);
    // cross-origin isolation enables multi-threaded AI
    headers.set('Cross-Origin-Opener-Policy', 'same-origin');
    headers.set('Cross-Origin-Embedder-Policy', 'require-corp');
    headers.set('Cross-Origin-Resource-Policy', 'same-origin');
    if (file.endsWith('.wasm')) headers.set('Content-Type', 'application/wasm');
    if (file.endsWith('.mjs') || file.endsWith('.js')) headers.set('Content-Type', 'text/javascript');
    return new Response(res.body, { status: res.status, headers });
  });

  ipcMain.handle('lema:save', async (_e, name, bytes) => {
    const r = await dialog.showSaveDialog(win, {
      title: 'שמירת קובץ',
      defaultPath: path.join(app.getPath('documents'), name),
      filters: [{ name: path.extname(name).slice(1).toUpperCase() || 'File', extensions: [path.extname(name).slice(1) || '*'] }, { name: 'All', extensions: ['*'] }],
    });
    if (r.canceled || !r.filePath) return null;
    fs.writeFileSync(r.filePath, Buffer.from(bytes));
    return r.filePath;
  });
  ipcMain.handle('lema:saveMany', async (_e, files) => {
    const r = await dialog.showOpenDialog(win, { title: 'בחרו תיקייה לשמירת הקבצים', defaultPath: app.getPath('documents'), properties: ['openDirectory', 'createDirectory'] });
    if (r.canceled || !r.filePaths[0]) return null;
    for (const f of files) fs.writeFileSync(path.join(r.filePaths[0], path.basename(f.name)), Buffer.from(f.bytes));
    return r.filePaths[0];
  });
  ipcMain.handle('lema:checkUpdates', async () => {
    if (!updater || !app.isPackaged) return { available: false, version: app.getVersion() };
    const r = await updater.checkForUpdates();
    const v = r && r.updateInfo ? r.updateInfo.version : app.getVersion();
    const available = !!(r && r.updateInfo && r.updateInfo.version !== app.getVersion() && r.isUpdateAvailable !== false);
    return { available, version: v };
  });
  ipcMain.handle('lema:installUpdate', async () => {
    if (updater && lastUpdate) { setImmediate(() => updater.quitAndInstall(true, true)); return true; }
    return false;
  });
  ipcMain.handle('lema:version', () => app.getVersion());

  createWindow();
  setupUpdater();
});

app.on('window-all-closed', () => app.quit());
