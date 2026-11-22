const { app, BrowserWindow, ipcMain, screen } = require("electron");
const path = require("path");

let win;
function createWindow() {
  const area = screen.getPrimaryDisplay().workArea;
  const width = 360;
  const height = 230;
  win = new BrowserWindow({
    width, height,
    x: Math.max(area.x, area.x + area.width - width - 18),
    y: Math.max(area.y + 80, Math.round(area.y + (area.height - height) / 2)),
    minWidth: 290, minHeight: 150,
    maxWidth: 620, maxHeight: 760,
    resizable: true,
    frame: false,
    transparent: false,
    backgroundColor: "#09111f",
    alwaysOnTop: true,
    skipTaskbar: false,
    show: false,
    webPreferences: {
      preload: path.join(__dirname, "preload.js"),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true
    }
  });
  win.loadFile(path.join(__dirname, "index.html"));
  win.once("ready-to-show", () => win.show());
  win.on("closed", () => { win = null; });
}
app.whenReady().then(createWindow);
app.on("window-all-closed", () => { if (process.platform !== "darwin") app.quit(); });
app.on("activate", () => { if (!win) createWindow(); });

ipcMain.on("window:minimize", () => win?.minimize());
ipcMain.on("window:close", () => win?.close());
ipcMain.on("window:toggle-top", (_event, enabled) => win?.setAlwaysOnTop(Boolean(enabled), "screen-saver"));
ipcMain.on("window:set-opacity", (_event, value) => {
  const n = Math.max(0.45, Math.min(1, Number(value) || 0.94));
  win?.setOpacity(n);
});
ipcMain.on("window:set-size", (_event, size) => {
  if (!win) return;
  const width = Math.max(290, Math.min(620, Number(size.width) || 360));
  const height = Math.max(150, Math.min(760, Number(size.height) || 230));
  win.setSize(Math.round(width), Math.round(height));
});
