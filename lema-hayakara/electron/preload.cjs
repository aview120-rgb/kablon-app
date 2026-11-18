const { contextBridge, ipcRenderer } = require('electron');
contextBridge.exposeInMainWorld('lemaNative', {
  saveFile: (name, bytes) => ipcRenderer.invoke('lema:save', name, bytes),
  checkUpdates: () => ipcRenderer.invoke('lema:checkUpdates'),
  installUpdate: () => ipcRenderer.invoke('lema:installUpdate'),
  version: () => ipcRenderer.invoke('lema:version'),
  onUpdateEvent: (cb) => ipcRenderer.on('lema:update', (_e, ev) => cb(ev)),
});
