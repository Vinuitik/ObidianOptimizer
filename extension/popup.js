// Popup controller. UI only — every network call is delegated to background.js
// via chrome.runtime.sendMessage so it runs with the extension's host permissions.
import { getConfig, api } from './config.js';

const send = (type, payload) => api.runtime.sendMessage({ type, payload });
const $ = (id) => document.getElementById(id);

function setStatus(el, msg, kind = 'info') {
  el.textContent = msg;
  el.className = `status ${kind}`;
}

// ── Tabs ──────────────────────────────────────────────────────────────────────
function showTab(name) {
  document.querySelectorAll('.tab').forEach(t => t.classList.toggle('active', t.dataset.tab === name));
  document.querySelectorAll('.panel').forEach(p => p.classList.toggle('active', p.dataset.panel === name));
}
document.querySelectorAll('.tab').forEach(t => t.addEventListener('click', () => showTab(t.dataset.tab)));
$('tab-settings-btn').addEventListener('click', () => {
  document.querySelectorAll('.tab').forEach(t => t.classList.remove('active'));
  document.querySelectorAll('.panel').forEach(p => p.classList.toggle('active', p.dataset.panel === 'settings'));
});

// ── Active-tab context (prefill) ───────────────────────────────────────────────
async function loadPageContext() {
  try {
    const [tab] = await api.tabs.query({ active: true, currentWindow: true });
    if (!tab) return;
    $('note-title').value = tab.title || '';
    $('dl-url').value = tab.url || '';
    $('ws-url').value = tab.url || '';

    // Pull any selected text from the page to pre-fill the note body.
    if (tab.id != null) {
      try {
        const [res] = await api.scripting.executeScript({
          target: { tabId: tab.id },
          func: () => window.getSelection().toString(),
        });
        if (res?.result) $('note-body').value = res.result;
      } catch { /* some pages (chrome://, store) block injection — ignore */ }
    }
  } catch { /* no tab access — leave blank */ }
}

// ── New note ───────────────────────────────────────────────────────────────────
async function loadFolders() {
  const sel = $('note-folder');
  sel.innerHTML = '<option value="">Vault root</option>';
  const res = await send('listFolders');
  if (res?.ok) {
    res.folders.forEach(f => {
      const opt = document.createElement('option');
      opt.value = f.path;
      opt.textContent = f.name;
      sel.appendChild(opt);
    });
  }
}

$('note-save').addEventListener('click', async () => {
  const btn = $('note-save');
  const title = $('note-title').value.trim();
  if (!title) return setStatus($('note-status'), 'Give the note a title.', 'err');

  const [tab] = await api.tabs.query({ active: true, currentWindow: true }).catch(() => [null]);
  btn.disabled = true;
  setStatus($('note-status'), 'Saving…', 'info');
  const res = await send('createNote', {
    title,
    body: $('note-body').value,
    folder: $('note-folder').value || null,
    sourceUrl: tab?.url || '',
  });
  btn.disabled = false;

  if (res?.ok) {
    setStatus($('note-status'), `Saved “${res.name}” ✓`, 'ok');
  } else if (res?.status === 401) {
    setStatus($('note-status'), 'Not signed in — open Settings ⚙ to sign in.', 'err');
  } else {
    setStatus($('note-status'), `Failed: ${res?.error || res?.status || 'unknown'}`, 'err');
  }
});

// ── Workspace ──────────────────────────────────────────────────────────────────
$('ws-save').addEventListener('click', async () => {
  const url = $('ws-url').value.trim();
  if (!url) return setStatus($('ws-status'), 'Paste a URL first.', 'err');

  $('ws-save').disabled = true;
  setStatus($('ws-status'), 'Downloading…', 'info');

  const res = await send('saveToWorkspace', { url });
  $('ws-save').disabled = false;

  if (res?.ok) {
    setStatus($('ws-status'), `Saved: ${res.filename} ✓`, 'ok');
  } else if (res?.status === 401) {
    setStatus($('ws-status'), 'Not signed in — open Settings ⚙ to sign in.', 'err');
  } else {
    setStatus($('ws-status'), `Failed: ${res?.error || res?.status || 'unknown'}`, 'err');
  }
});

// ── Download ───────────────────────────────────────────────────────────────────
let pollTimer = null;

$('dl-start').addEventListener('click', async () => {
  const url = $('dl-url').value.trim();
  if (!url) return setStatus($('dl-status'), 'Paste a URL first.', 'err');

  clearInterval(pollTimer);
  $('dl-progress').classList.remove('hidden');
  setBar(0, 'queued', '');
  setStatus($('dl-status'), 'Starting…', 'info');

  const res = await send('startDownload', { url });
  if (!res?.ok) {
    return setStatus($('dl-status'), `Can't reach downloader (${res?.error || res?.status}). Check Settings ⚙.`, 'err');
  }
  setStatus($('dl-status'), '', 'info');
  pollTimer = setInterval(() => pollJob(res.jobId), 1000);
});

async function pollJob(jobId) {
  const res = await send('downloadStatus', { jobId });
  if (!res?.ok) return;
  const job = res.job;
  setBar(job.progress || 0, job.status, `${job.speed || ''} ${job.eta ? '· ' + job.eta : ''}`.trim());

  if (job.status === 'done') {
    clearInterval(pollTimer);
    const file = (job.filename || '').split(/[/\\]/).pop();
    setStatus($('dl-status'), `Downloaded: ${file} ✓`, 'ok');
  } else if (job.status === 'error') {
    clearInterval(pollTimer);
    setStatus($('dl-status'), `Failed: ${job.error || 'download error'}`, 'err');
  }
}

function setBar(pct, state, speed) {
  $('dl-bar').style.width = `${Math.max(0, Math.min(100, pct))}%`;
  $('dl-pct').textContent = `${(pct || 0).toFixed(0)}%`;
  $('dl-speed').textContent = speed || '';
  const badge = $('dl-state');
  badge.textContent = state;
  badge.className = `badge ${state === 'done' ? 'done' : state === 'error' ? 'error' : ''}`;
}

// ── Settings ────────────────────────────────────────────────────────────────────
async function loadSettings() {
  const cfg = await getConfig();
  $('cfg-obsidian').value = cfg.obsidianApi;
  refreshAuthLine();
}

$('cfg-save').addEventListener('click', async () => {
  await send('setConfig', { obsidianApi: $('cfg-obsidian').value.trim() });
  setStatus($('settings-status'), 'Endpoint saved.', 'ok');
  loadFolders();
});

$('login-btn').addEventListener('click', async () => {
  const username = $('login-user').value.trim();
  const password = $('login-pass').value;
  if (!username || !password) return setStatus($('settings-status'), 'Enter username and password.', 'err');
  setStatus($('settings-status'), 'Signing in…', 'info');
  const res = await send('login', { username, password });
  if (res?.ok) {
    setStatus($('settings-status'), 'Signed in ✓', 'ok');
    $('login-pass').value = '';
    refreshAuthLine();
    loadFolders();
  } else {
    setStatus($('settings-status'), `Sign-in failed (${res?.status || res?.error || 'check endpoint'}).`, 'err');
  }
});

async function refreshAuthLine() {
  const res = await send('checkAuth');
  $('auth-line').textContent = res?.ok ? 'Signed in to ObsidianOptimizer ✓' : 'Sign in to ObsidianOptimizer';
}

// ── Init ──────────────────────────────────────────────────────────────────────
loadPageContext();
loadFolders();
loadSettings();
