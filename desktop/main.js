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
//
// LAUNCHER (self-host bundle): this shell also runs the user's local docker stack. Boot flow
// is in boot() below; logic lives in lib/ (see FLOWS.md).
const { app, BrowserWindow, dialog, shell, Notification, Tray, Menu, ipcMain, nativeImage } = require('electron');
const path = require('path');
const quotes = require('./quotes.json');
const launcher = require('./lib/launcher');
const docker = require('./lib/docker');
const { defaultVault } = require('./lib/paths');
const C = require('./lib/config');

// Dev escape hatch: OBSOPT_URL skips the launcher and loads that site (old thin-shell behaviour).
const APP_URL_OVERRIDE = process.env.OBSOPT_URL || null;
let win;
let tray;
let allowClose = false;
let busy = false;           // an up/stop/update is in flight

const say = (m) => { if (win && !win.isDestroyed()) win.webContents.send('oo:status', m); };
const showPage = async (f, msg) => { await win.loadFile(path.join(__dirname, 'wizard', f)); if (msg) say(msg); };

function createWindow() {
  win = new BrowserWindow({
    width: 1280,
    height: 860,
    backgroundColor: '#0f1115',
    title: 'Obsidian Optimizer',
    autoHideMenuBar: true,
    webPreferences: { contextIsolation: true, nodeIntegration: false, preload: path.join(__dirname, 'preload.js') },
  });

  if (APP_URL_OVERRIDE) win.loadURL(APP_URL_OVERRIDE); else boot();

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
  win.on('minimize', onMinimize);
}

// Shared by close (blocking dialog) and minimize (non-blocking notification). Errors are
// logged instead of silently swallowed to false — a swallowed exception here used to look
// identical to "duty genuinely done," making a real bug (e.g. not signed in inside this
// window's own cookie jar, separate from your regular browser) undiagnosable from outside.
async function checkUnfinished() {
  try {
    return await win.webContents.executeJavaScript(`(async () => {
      const today = new Date().toISOString().slice(0, 10);
      if (localStorage.getItem('obsOpt_learnDoneDate') !== today) return true;
      const r = await fetch('/api/review?offset=0&limit=1', { credentials: 'same-origin', cache: 'no-store' });
      if (!r.ok) throw new Error('review probe HTTP ' + r.status);
      const j = await r.json();
      return Array.isArray(j.notes) && j.notes.length > 0;
    })()`);
  } catch (err) {
    console.error('[quit-guard] duty probe failed — treating as done, but this is likely a bug:', err);
    return false;   // offline / server down / not signed in here → don't nag on what we can't verify
  }
}

function randomQuote() { return quotes[Math.floor(Math.random() * quotes.length)]; }

async function onCloseAttempt(e) {
  if (allowClose) return;          // second pass after the user confirmed quit
  e.preventDefault();

  const unfinished = await checkUnfinished();
  if (!unfinished) { allowClose = true; win.close(); return; }   // caught up → quit silently

  const q = randomQuote();
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

// Minimize can't be intercepted/cancelled the way close can (Electron has no "about to
// minimize" hook) — so unlike close, this only reacts after the fact with a non-blocking
// OS notification rather than snapping the window back open (that flicker read as janky).
async function onMinimize() {
  const unfinished = await checkUnfinished();
  if (!unfinished) return;
  const q = randomQuote();
  new Notification({
    title: 'Are you a quitter?',
    body: `${q.text}\n— ${q.author}`,
  }).show();
}

// ── Launcher ────────────────────────────────────────────────────────────────
// boot: not installed -> wizard; docker missing -> install help; else up -d -> wait healthy -> load UI.
async function boot() {
  if (!launcher.isInstalled()) return showPage('wizard.html');
  return startStack();
}

async function ensureDocker() {
  const d = await docker.detectDocker();
  if (d.state === 'ok') return true;
  const why = { 'no-docker': 'Docker is not installed.', 'no-compose': 'Docker Compose v2 is missing (update Docker Desktop).', 'no-daemon': 'Docker is installed but not running.' }[d.state];
  await showPage('status.html', { title: 'Docker needed', msg: why, docker: true });
  return false;
}

async function startStack() {
  if (busy) return;
  busy = true;
  try {
    await showPage('status.html', { title: 'Starting...', msg: 'Checking Docker', docker: false });
    if (!(await ensureDocker())) return;
    say({ msg: 'Starting your stack (first start downloads images, this can take several minutes)' });
    await launcher.up({ onLine: (l) => say({ line: l }) });
    say({ msg: 'Waiting for the app to become healthy' });
    if (await launcher.waitUntilHealthy()) await win.loadURL(launcher.localUrl());
    else say({ title: 'Not healthy yet', msg: 'The app did not answer in time. Use the tray menu: Offline, then Online to retry.' });
  } catch (e) {
    say({ title: 'Start failed', msg: String(e.message || e) });
  } finally { busy = false; refreshTray(); }
}

async function goOffline() {
  if (busy) return; busy = true;
  try { await showPage('status.html', { title: 'Stopping...', msg: '' }); await launcher.stop({ onLine: (l) => say({ line: l }) }); say({ title: 'Offline', msg: 'Your instance is stopped. Tray: Online to start it.' }); }
  catch (e) { say({ title: 'Stop failed', msg: String(e.message || e) }); }
  finally { busy = false; refreshTray(); }
}

async function runUpdateFlow() {
  if (busy || !launcher.isInstalled()) return; busy = true;
  try {
    await showPage('status.html', { title: 'Updating...', msg: 'Backing up, then pulling the new version' });
    if (!(await ensureDocker())) return;
    const r = await launcher.update((l) => say({ line: l }));
    const text = {
      'up-to-date': 'You are on the latest version.',
      'launcher-too-old': 'A newer launcher is required for this release - download the latest launcher app.',
      updated: `Updated ${r.from} -> ${r.to}.`,
      'rolled-back': `Update to ${r.to} failed (${r.error}). Rolled back to ${r.from}; a database backup is in the data folder.`,
      'rollback-failed': `Update failed AND rollback is unhealthy (${r.error}). Backups are in the data folder.`,
      'backup-failed': `Backup failed, update aborted (${r.error}). Is the stack online?`,
    }[r.status];
    say({ title: r.status === 'updated' || r.status === 'up-to-date' ? 'Done' : 'Update problem', msg: text });
    if (r.status === 'updated' || r.status === 'rolled-back') await win.loadURL(launcher.localUrl());
  } catch (e) { say({ title: 'Update failed', msg: String(e.message || e) }); }
  finally { busy = false; refreshTray(); }
}

let online = false;
async function refreshTray() {
  if (!tray) return;
  online = launcher.isInstalled() ? await launcher.isOnline().catch(() => false) : false;
  tray.setContextMenu(Menu.buildFromTemplate([
    { label: online ? 'Status: Online' : 'Status: Offline', enabled: false },
    { label: online ? 'Go Offline (stop)' : 'Go Online (start)', enabled: !busy && launcher.isInstalled(), click: () => (online ? goOffline() : startStack()) },
    { label: 'Open app', enabled: launcher.isInstalled(), click: () => { if (win) { win.show(); win.focus(); } else createWindow(); if (online && win) win.loadURL(launcher.localUrl()); } },
    { label: 'Check for updates', enabled: !busy && launcher.isInstalled(), click: runUpdateFlow },
    { type: 'separator' },
    { label: 'Quit', click: () => { allowClose = true; app.quit(); } },   // containers keep running; use Go Offline first to stop
  ]));
}

ipcMain.handle('oo:defaults', () => ({ vaultPath: defaultVault(), platform: process.platform }));
ipcMain.handle('oo:pick-folder', async () => {
  const r = await dialog.showOpenDialog(win, { properties: ['openDirectory', 'createDirectory'] });
  return r.canceled ? null : r.filePaths[0];
});
ipcMain.handle('oo:open-external', (_e, url) => { if (/^https:\/\//.test(url)) shell.openExternal(url); });
ipcMain.handle('oo:recheck', () => startStack());
ipcMain.handle('oo:install', async (_e, input) => {
  try {
    if (!(await ensureDockerForWizard())) return { ok: false, error: 'Docker is not ready - install/start Docker Desktop first.' };
    await launcher.install(input, (m) => say(m));
    startStack();
    return { ok: true };
  } catch (e) { return { ok: false, error: String(e.message || e), fields: e.fields }; }
});
async function ensureDockerForWizard() { return (await docker.detectDocker()).state === 'ok'; }

app.whenReady().then(() => {
  createWindow();
  try {
    tray = new Tray(nativeImage.createFromPath(path.join(__dirname, 'icon.png')).resize({ width: 22, height: 22 }));
    tray.setToolTip('Obsidian Optimizer');
    refreshTray();
    setInterval(() => { if (!busy) refreshTray(); }, 30000);
  } catch (e) { console.error('[tray] unavailable', e); }
});
app.on('window-all-closed', () => app.quit());
app.on('activate', () => { if (BrowserWindow.getAllWindows().length === 0) createWindow(); });
