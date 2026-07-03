// Popup controller. UI only — every network call is delegated to background.js
// via runtime.sendMessage so it runs with the extension's host permissions.
import { getConfig, api } from './config.js';

const send = (type, payload) => api.runtime.sendMessage({ type, payload });
const $ = (id) => document.getElementById(id);

function setStatus(el, msg, kind = 'info') {
  el.textContent = msg;
  el.className = `status ${kind}`;
}

// ── Panels (capture / settings) ─────────────────────────────────────────────────
function showPanel(name) {
  document.querySelectorAll('.panel').forEach(p =>
    p.classList.toggle('active', p.dataset.panel === name));
}
$('tab-settings-btn').addEventListener('click', () => { showPanel('settings'); loadSettings(); });
$('back-btn').addEventListener('click', () => showPanel('capture'));

// ── Live detection hint ──────────────────────────────────────────────────────────
const URL_RE = /^https?:\/\/\S+$/i;
const VIDEO_HOST_RE = /(?:^|\.)(youtube\.com|youtu\.be|vimeo\.com|dailymotion\.com)$/i;
const MEDIA_EXT_RE = /\.(pdf|mp4|mov|mkv|webm|avi|mp3|m4a|wav|ogg|flac)(?:[?#]|$)/i;

function detectLabel(text) {
  const t = (text || '').trim();
  if (!t) return '';
  if (!URL_RE.test(t)) return '📝 Plain text → notes synthesized for review';
  let host = '';
  try { host = new URL(t).host; } catch { return '📝 Text → notes synthesized'; }
  if (VIDEO_HOST_RE.test(host)) return '🎬 Video → downloaded to watch + notes generated';
  if (MEDIA_EXT_RE.test(t)) return '📎 File link → saved to workspace + notes generated';
  return '🔗 Web page → notes generated from it';
}

$('cap-input').addEventListener('input', (e) => {
  $('cap-hint').textContent = detectLabel(e.target.value);
});

// ── Prefill from the active tab ────────────────────────────────────────────────
async function prefill() {
  try {
    const [tab] = await api.tabs.query({ active: true, currentWindow: true });
    if (tab?.url && /^https?:/i.test(tab.url) && !$('cap-input').value) {
      $('cap-input').value = tab.url;
      $('cap-hint').textContent = detectLabel(tab.url);
    }
  } catch { /* no tab access — leave blank */ }
}

// ── Smart one-click: capture the active tab ─────────────────────────────────────
$('cap-page').addEventListener('click', capturePage);

async function capturePage() {
  let tab;
  try { [tab] = await api.tabs.query({ active: true, currentWindow: true }); }
  catch { /* no tab access */ }
  if (!tab?.url || !/^https?:/i.test(tab.url)) {
    return setStatus($('cap-status'), 'This page can’t be captured — open a normal web page.', 'err');
  }
  $('cap-page').disabled = true;
  $('cap-send').disabled = true;
  setStatus($('cap-status'), 'Capturing this page…', 'info');
  const res = await send('capturePage', { url: tab.url, tabId: tab.id });
  $('cap-page').disabled = false;
  handleResult(res);
}

// ── Send (text / URL) ─────────────────────────────────────────────────────────
$('cap-send').addEventListener('click', () => submit());

async function submit() {
  const text = $('cap-input').value.trim();
  if (!text) return setStatus($('cap-status'), 'Paste something or drop a file first.', 'err');

  $('cap-send').disabled = true;
  setStatus($('cap-status'), 'Queuing…', 'info');
  const res = await send('routeText', { text });
  handleResult(res);
}

// ── Drag & drop a file ──────────────────────────────────────────────────────────
const drop = $('cap-input');
['dragenter', 'dragover'].forEach(ev =>
  drop.addEventListener(ev, (e) => { e.preventDefault(); drop.classList.add('drag'); }));
['dragleave', 'drop'].forEach(ev =>
  drop.addEventListener(ev, () => drop.classList.remove('drag')));

drop.addEventListener('drop', async (e) => {
  e.preventDefault();
  const file = e.dataTransfer?.files?.[0];
  if (!file) return;
  $('cap-send').disabled = true;
  setStatus($('cap-status'), `Uploading “${file.name}”…`, 'info');
  try {
    const dataB64 = await fileToB64(file);
    const res = await send('uploadFile', { name: file.name, type: file.type, dataB64 });
    handleResult(res);
  } catch (err) {
    $('cap-send').disabled = false;
    setStatus($('cap-status'), `Couldn't read the file: ${err}`, 'err');
  }
});

function fileToB64(file) {
  return new Promise((resolve, reject) => {
    const r = new FileReader();
    r.onload = () => resolve(r.result.split(',')[1]); // strip data: prefix
    r.onerror = () => reject(r.error);
    r.readAsDataURL(file);
  });
}

function handleResult(res) {
  $('cap-send').disabled = false;
  if (res?.ok) {
    setStatus($('cap-status'), `${res.detail || 'Queued'} ✓`, 'ok');
    $('cap-input').value = '';
    $('cap-hint').textContent = '';
  } else if (res?.status === 401) {
    setStatus($('cap-status'), 'Not signed in — open Settings ⚙ to sign in.', 'err');
  } else {
    setStatus($('cap-status'), `Failed: ${res?.error || res?.status || 'unknown'}`, 'err');
  }
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
  } else {
    setStatus($('settings-status'), `Sign-in failed (${res?.status || res?.error || 'check endpoint'}).`, 'err');
  }
});

async function refreshAuthLine() {
  const res = await send('checkAuth');
  $('auth-line').textContent = res?.ok
    ? 'Signed in to ObsidianOptimizer ✓' : 'Sign in to ObsidianOptimizer';
}

// ── Init ──────────────────────────────────────────────────────────────────────
prefill();
