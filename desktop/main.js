// Obsidian Optimizer — desktop shell (the "quit-breaker").
//
// A THIN wrapper: it loads the live site and does the ONE thing a browser can't —
// intercept the real window-close and, if today's duty is unfinished, pressure you
// with a great-man quote before letting you quit. All app logic lives in the web app
// (which auto-updates on every deploy); this shell only owns the close-guard.
//
// Duty check mirrors the web QuitGuard (utils/dailyDuty.js): unfinished =
//   (no Learn item processed today  ->  localStorage 'obsOpt_learnDoneDate' != today)
//   OR (a review is still due  ->  GET /api/review?limit=1 returns a note).
// Evaluated IN the loaded page so it shares that page's session cookie + origin.
const { app, BrowserWindow, dialog, shell } = require('electron');
const quotes = require('./quotes.json');

const APP_URL = process.env.OBSOPT_URL || 'https://obsidianoptimizer.uk';
let win;
let allowClose = false;

function createWindow() {
  win = new BrowserWindow({
    width: 1280,
    height: 860,
    backgroundColor: '#0f1115',
    title: 'Obsidian Optimizer',
    autoHideMenuBar: true,
    webPreferences: { contextIsolation: true, nodeIntegration: false },
  });

  win.loadURL(APP_URL);
  // ?pwa=1 forces the narrow app shell (ResponsiveApp) — the "akin to the phone app" view.
  // Drop it (or set OBSOPT_URL) if you'd rather the full desktop site.

  // External links (http/https to other origins) open in the system browser, not in-shell.
  win.webContents.setWindowOpenHandler(({ url }) => { shell.openExternal(url); return { action: 'deny' }; });

  // Ctrl/Cmd+R and F5 force a real reload, bypassing Chromium's HTTP cache — the shell
  // has no menu bar (autoHideMenuBar) so Electron's default reload accelerator isn't
  // wired without this. Same fix as the web RefreshButton: a plain in-app navigation
  // never happens on its own, so without an explicit reload path the only way users
  // found to pick up a new deploy was reinstalling.
  win.webContents.on('before-input-event', (event, input) => {
    const isReload = input.type === 'keyDown'
      && (input.key === 'F5' || ((input.control || input.meta) && input.key.toLowerCase() === 'r'));
    if (isReload) {
      event.preventDefault();
      win.webContents.reloadIgnoringCache();
    }
  });

  win.on('close', onCloseAttempt);
}

async function onCloseAttempt(e) {
  if (allowClose) return;          // second pass after the user confirmed quit
  e.preventDefault();

  let unfinished = false;
  try {
    unfinished = await win.webContents.executeJavaScript(`(async () => {
      const today = new Date().toISOString().slice(0, 10);
      if (localStorage.getItem('obsOpt_learnDoneDate') !== today) return true;
      try {
        const r = await fetch('/api/review?offset=0&limit=1', { credentials: 'same-origin', cache: 'no-store' });
        const j = await r.json();
        return Array.isArray(j.notes) && j.notes.length > 0;
      } catch { return false; }   // offline / server down → don't nag on what we can't verify
    })()`);
  } catch { unfinished = false; }

  if (!unfinished) { allowClose = true; win.close(); return; }   // caught up → quit silently

  const q = quotes[Math.floor(Math.random() * quotes.length)];
  const { response } = await dialog.showMessageBox(win, {
    type: 'warning',
    title: 'Are you a quitter?',
    message: 'Are you a quitter?',
    detail: `You still have reviews due or Learn items to process today.\n\n“${q.text}”\n— ${q.author}`,
    buttons: ['No — back to work', "I'm a quitter"],
    defaultId: 0,
    cancelId: 0,
    noLink: true,
  });

  if (response === 1) { allowClose = true; win.close(); }   // chose to quit → let it close
}

app.whenReady().then(createWindow);
app.on('window-all-closed', () => app.quit());
app.on('activate', () => { if (BrowserWindow.getAllWindows().length === 0) createWindow(); });
