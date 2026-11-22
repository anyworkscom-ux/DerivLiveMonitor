const { contextBridge, ipcRenderer } = require("electron");
contextBridge.exposeInMainWorld("zichanWindow", {
  minimize: () => ipcRenderer.send("window:minimize"),
  close: () => ipcRenderer.send("window:close"),
  setAlwaysOnTop: (value) => ipcRenderer.send("window:toggle-top", value),
  setOpacity: (value) => ipcRenderer.send("window:set-opacity", value),
  setSize: (width, height) => ipcRenderer.send("window:set-size", { width, height })
});
