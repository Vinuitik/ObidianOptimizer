const { contextBridge, ipcRenderer } = require('electron');
contextBridge.exposeInMainWorld('oo', {
  defaults: () => ipcRenderer.invoke('oo:defaults'),
  pickFolder: () => ipcRenderer.invoke('oo:pick-folder'),
  install: (input) => ipcRenderer.invoke('oo:install', input),
  recheckDocker: () => ipcRenderer.invoke('oo:recheck'),
  openExternal: (url) => ipcRenderer.invoke('oo:open-external', url),
  onStatus: (cb) => ipcRenderer.on('oo:status', (_e, m) => cb(m)),
});
