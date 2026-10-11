// Popup controller. UI only — every network call is delegated to background.js
// via runtime.sendMessage so it runs with the extension's host permissions.
import { getConfig, getCreds, setCreds, clearCreds, api } from './config.js';

const send = (type, payload) => api.runtime.sendMessage({ type, payload });
const $ = (id) => document.getElementById(id);

function setStatus(el, msg, kind = 'info') {
  el.textContent = msg;
  el.className = `status ${kind}`;
}

// Human labels for background.js result kinds.
const KIND_LABEL = { text: 'page text', video: 'video', media: 'file', page: 'web page' };
const isOffline = (res) => res && !res.ok && (res.status === 0 || /unreachable/i.test(res.error || ''));

// Learn page lives at the site root next to the API base (…/api → …/learn).
async function learnUrl() {
  const { obsidianApi } = await getConfig();
  return `${obsidianApi.replace(/\/api\/?$/, '')}/learn`;
}

// ── Auth gate: signed out → sign-in card instead of capture controls ─────────────
let signedIn = true;
async function applyAuth() {
  const res = await send('checkAuth');
  signedIn = !!res?.ok;
  $('signin-card').hidden = signedIn;
  $('capture-main').hidden = !signedIn;
  if (!signedIn) {
    const { authUser } = await getCreds();
    if (authUser && !$('si-user').value) $('si-user').value = authUser;
  }
}

// ── Panels (capture / settings) ─────────────────────────────────────────────────
function showPanel(name) {
  document.querySelectorAll('.panel').forEach(p =>
    p.classList.toggle('active', p.dataset.panel === name));
}
$('tab-settings-btn').addEventListener('click', () => { showPanel('settings'); loadSettings(); });
$('back-btn').addEventListener('click', () => { showPanel('capture'); applyAuth(); });

// ── Live detection hint ──────────────────────────────────────────────────────────
const URL_RE = /^https?:\/\/\S+$/i;
const VIDEO_HOST_RE = /(?:^|\.)(youtube\.com|youtu\.be|vimeo\.com|dailymotion\.com)$/i;
const MEDIA_EXT_RE = /\.(pdf|mp4|mov|mkv|webm|avi|mp3|m4a|wav|ogg|flac)(?:[?#]|$)/i;

function detectLabel(text) {
  const t = (text || '').trim();
  if (!t) return '';
  if (!URL_RE.test(t)) return 'Plain text → notes synthesized for review';
  let host = '';
  try { host = new URL(t).host; } catch { return 'Text → notes synthesized'; }
  if (VIDEO_HOST_RE.test(host)) return 'Video → downloaded to watch + notes generated';
  if (MEDIA_EXT_RE.test(t)) return 'File link → saved to workspace + notes generated';
  return 'Web page → notes generated from it';
}

$('cap-input').addEventListener('input', (e) => {
  $('cap-hint').textContent = detectLabel(e.target.value);
});

// ── Track picker (tracks/FLOWS.md Phase 1b) ──────────────────────────────────────
// "Just capture" (default, today's exact behavior) / an existing active track / "+ New
// track…" (reveals an inline title input). Shared by both capture paths below.
$('cap-track').addEventListener('change', () => {
  $('cap-track-title').hidden = $('cap-track').value !== '__new__';
});

async function loadTracks() {
  const res = await send('listTracks');
  if (!res?.ok) return;   // not signed in yet / embedder-less list failure — leave the default option
  const sel = $('cap-track');
  for (const t of res.tracks || []) {
    if (t.status && t.status !== 'active') continue;
    const opt = document.createElement('option');
    opt.value = String(t.id);
    opt.textContent = t.title;
    sel.insertBefore(opt, sel.querySelector('option[value="__new__"]'));
  }
}

// { trackId, newTrackTitle } to attach to a capture payload, or {} for "Just capture".
function trackSelection() {
  const v = $('cap-track').value;
  if (!v) return {};
  if (v === '__new__') {
    const title = $('cap-track-title').value.trim();
    return title ? { newTrackTitle: title } : {};
  }
  return { trackId: Number(v) };
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
  $('cap-links').hidden = true;
  $('cap-page').disabled = true;
  $('cap-send').disabled = true;
  setStatus($('cap-status'), 'Capturing this page…', 'info');
  const res = await send('capturePage', { url: tab.url, tabId: tab.id, ...trackSelection() });
  $('cap-page').disabled = false;
  handleResult(res);
}

// ── Subscribe to this page → POST /feed/sources (new items land in Learn ▸ Feed) ─────
// Two-step: Preview (resolveSource, nothing persisted) shows what you'd get, THEN
// Confirm subscribe actually creates the source (feed/FLOWS_feed.md §3).
let pendingSubscribeUrl = null;

$('subscribe-page').addEventListener('click', async () => {
  const open = $('subscribe-form').hidden && $('sub-preview').hidden;
  $('subscribe-form').hidden = !open;
  $('sub-preview').hidden = true;
  $('subscribe-page').setAttribute('aria-expanded', String(open));
  setStatus($('sub-status'), '', 'info');
  if (!open) return;
  try {
    const [tab] = await api.tabs.query({ active: true, currentWindow: true });
    if (tab?.url && /^https?:/i.test(tab.url)) {
      $('sub-url').value = tab.url;
      previewSource();   // one click → preview straight away; Confirm is the 2nd click
    }
  } catch { /* no tab access — leave field blank */ }
});

$('sub-cancel').addEventListener('click', () => {
  $('subscribe-form').hidden = true;
  $('sub-preview').hidden = true;
  $('subscribe-page').setAttribute('aria-expanded', 'false');
  setStatus($('sub-status'), '', 'info');
});

$('sub-back').addEventListener('click', () => {
  $('sub-preview').hidden = true;
  $('subscribe-form').hidden = false;
  setStatus($('sub-status'), '', 'info');
});

$('sub-preview-btn').addEventListener('click', previewSource);

async function previewSource() {
  const url = $('sub-url').value.trim();
  if (!url) return setStatus($('sub-status'), 'Enter a URL.', 'err');

  $('sub-preview-btn').disabled = true;
  setStatus($('sub-status'), 'Checking…', 'info');
  const res = await send('resolveSource', { url });
  $('sub-preview-btn').disabled = false;
  if (!res?.ok) {
    if (res?.status === 401) { applyAuth(); return; }
    setStatus($('sub-status'), isOffline(res)
      ? 'You appear to be offline. Check your connection and try again.'
      : `Failed: ${res?.error || res?.status || 'unknown'}`, 'err');
    return;
  }

  pendingSubscribeUrl = url;
  $('sub-preview-title').textContent = `${res.title || url} (${res.kind})`;
  $('sub-preview-items').innerHTML = '';
  (res.preview || []).forEach(item => {
    const li = document.createElement('li');
    li.textContent = item.title || item.itemUrl;
    $('sub-preview-items').appendChild(li);
  });
  if (!res.preview?.length) {
    const li = document.createElement('li');
    li.textContent = '(no items yet — you\'ll still get future ones)';
    $('sub-preview-items').appendChild(li);
  }
  $('subscribe-form').hidden = true;
  $('sub-preview').hidden = false;
  setStatus($('sub-status'), '', 'info');
}

$('sub-confirm').addEventListener('click', async () => {
  const url = pendingSubscribeUrl;
  if (!url) return;

  $('sub-confirm').disabled = true;
  setStatus($('sub-status'), 'Subscribing…', 'info');
  const res = await send('subscribeSource', { url });
  $('sub-confirm').disabled = false;
  if (res?.ok) {
    setStatus($('sub-status'), 'Subscribed. New items will appear in Learn > Feed.', 'ok');
    $('subscribe-page').setAttribute('aria-expanded', 'false');
    $('sub-url').value = '';
    pendingSubscribeUrl = null;
    $('sub-preview').hidden = true;
  } else if (res?.status === 401) {
    applyAuth();
  } else if (isOffline(res)) {
    setStatus($('sub-status'), 'You appear to be offline. Check your connection and try again.', 'err');
  } else {
    setStatus($('sub-status'), `Failed: ${res?.error || res?.status || 'unknown'}`, 'err');
  }
});

// ── Send (text / URL) ─────────────────────────────────────────────────────────
$('cap-send').addEventListener('click', () => submit());

async function submit() {
  const text = $('cap-input').value.trim();
  if (!text) return setStatus($('cap-status'), 'Paste something or drop a file first.', 'err');

  $('cap-send').disabled = true;
  setStatus($('cap-status'), 'Queuing…', 'info');
  const res = await send('routeText', { text, ...trackSelection() });
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

async function showLearnLink(show) {
  $('cap-links').hidden = !show;
  if (show) $('cap-open-learn').href = await learnUrl();
}

function handleResult(res) {
  $('cap-send').disabled = false;
  showLearnLink(false);
  if (res?.ok) {
    const label = KIND_LABEL[res.kind];
    setStatus($('cap-status'), `${res.detail || (label ? `Queued as ${label}` : 'Queued')}.`, 'ok');
    showLearnLink(true);
    $('cap-input').value = '';
    $('cap-hint').textContent = '';
    if ($('cap-track').value === '__new__') loadTracks();   // the new track now exists — refresh + reset
    $('cap-track').value = '';
    $('cap-track-title').value = '';
    $('cap-track-title').hidden = true;
  } else if (res?.duplicate) {
    // Distinct (ember) from errors: you already captured this — a re-share, not a failure.
    setStatus($('cap-status'), res.detail || 'Already in your inbox.', 'warn');
    showLearnLink(true);
  } else if (res?.status === 401) {
    applyAuth();
  } else if (isOffline(res)) {
    setStatus($('cap-status'), 'You appear to be offline or the server is unreachable. Check your connection and try again.', 'err');
  } else {
    setStatus($('cap-status'), `Failed: ${res?.error || res?.status || 'unknown'}`, 'err');
  }
}

// ── Settings ────────────────────────────────────────────────────────────────────
async function loadSettings() {
  const cfg = await getConfig();
  $('cfg-obsidian').value = cfg.obsidianApi;
  $('cfg-agent-token').value = cfg.agentWsToken || '';
  // Prefill remembered credentials so the user never re-types (password managers don't
  // fire reliably in an extension popup — this is the reliable substitute).
  const { authUser, authPass } = await getCreds();
  if (authUser) $('login-user').value = authUser;
  if (authPass) $('login-pass').value = authPass;
  $('login-remember').checked = !!authUser;
  refreshAuthLine();
}

$('cfg-save').addEventListener('click', async () => {
  await send('setConfig', {
    obsidianApi: $('cfg-obsidian').value.trim(),
    agentWsToken: $('cfg-agent-token').value.trim(),
  });
  setStatus($('cfg-status'), 'Settings saved.', 'ok');
});

// One login routine for both the capture-panel card and the settings form.
async function doLogin(userEl, passEl, rememberEl, statusEl) {
  const username = userEl.value.trim();
  const password = passEl.value;
  if (!username || !password) return setStatus(statusEl, 'Enter username and password.', 'err');
  setStatus(statusEl, 'Signing in…', 'info');
  const res = await send('login', { username, password });
  if (res?.ok) {
    // Remember (or forget) so background.js can silently re-login on a lapsed session.
    if (rememberEl.checked) await setCreds(username, password);
    else await clearCreds();
    passEl.value = '';
    setStatus(statusEl, 'Signed in.', 'ok');
    await applyAuth();
    refreshAuthLine();
  } else if (res?.status === 401) {
    setStatus(statusEl, 'Wrong username or password.', 'err');
  } else if (isOffline(res) || res?.status === 0 || res?.error) {
    setStatus(statusEl, 'Can’t reach the server. Check your connection and try again.', 'err');
  } else {
    setStatus(statusEl, `Sign-in failed (${res?.status || 'unknown'}).`, 'err');
  }
}

// <form> submit covers the button and Enter in either field.
$('login-form').addEventListener('submit', (e) => {
  e.preventDefault();
  doLogin($('login-user'), $('login-pass'), $('login-remember'), $('settings-status'));
});
$('signin-card').addEventListener('submit', (e) => {
  e.preventDefault();
  doLogin($('si-user'), $('si-pass'), $('si-remember'), $('si-status'));
});

$('signout-btn').addEventListener('click', async () => {
  await clearCreds();   // also stops background.js silently re-logging in
  // Deviation from "popup never calls the network": background.js has no logout handler
  // and is out of scope for the UI redesign. Extension pages hold host_permissions, so this works.
  let ok = false;
  try {
    const { obsidianApi } = await getConfig();
    ok = (await fetch(`${obsidianApi}/logout`, { method: 'POST', credentials: 'include' })).ok;
  } catch { /* offline */ }
  await applyAuth();
  setStatus($('settings-status'), ok ? 'Signed out.' : 'Could not reach the server to end the session; saved sign-in was forgotten.', ok ? 'info' : 'err');
  refreshAuthLine();
});

async function refreshAuthLine() {
  const res = await send('checkAuth');
  const on = !!res?.ok;
  const { authUser } = await getCreds();
  $('auth-line').textContent = on ? 'Account' : 'Sign in';
  $('auth-user').textContent = authUser || 'your account';
  $('auth-user-row').hidden = !on;
  $('login-fields').hidden = on;
}

// ── Agent activity feed ─────────────────────────────────────────────────────────
function short(u) { try { return new URL(u).host; } catch { return (u || '').slice(0, 40); } }
function agentEventLabel(e) {
  if (e.event === 'start') return `escalating ${short(e.ref)} — ${e.error || 'failed'}`;
  if (e.event === 'tool')  return `tool: ${e.tool}${e.arg ? ' · ' + short(e.arg) : ''}`;
  if (e.event === 'done') {
    if (e.status === 'recovered') return `recovered → ${e.detail || ''}`;
    if (e.status === 'gave_up')   return `gave up — ${e.detail || ''}`;
    return `exhausted — ${e.detail || ''}`;
  }
  return JSON.stringify(e);
}
async function renderAgentFeed() {
  const { agentLog = [] } = await api.storage.local.get('agentLog');
  const panel = $('agent-panel'), feed = $('agent-feed'), badge = $('agent-badge');
  panel.hidden = agentLog.length === 0;
  if (!agentLog.length) return;
  feed.innerHTML = '';
  for (const e of agentLog.slice(-12).reverse()) {
    const li = document.createElement('li');
    li.textContent = agentEventLabel(e);
    li.className = e.event === 'done'
      ? (e.status === 'recovered' ? 'ev-ok' : 'ev-fail') : 'ev';
    feed.appendChild(li);
  }
  const last = agentLog[agentLog.length - 1];
  const running = last.event !== 'done';
  badge.textContent = running ? '● working' : (last.status === 'recovered' ? '✓ fixed' : '✕ ' + last.status);
  badge.className = `agent-badge ${running ? 'run' : (last.status === 'recovered' ? 'ok' : 'fail')}`;
}
api.storage.onChanged.addListener((changes, area) => {
  if (area === 'local' && changes.agentLog) renderAgentFeed();
});

// ── Init ──────────────────────────────────────────────────────────────────────
applyAuth();
renderAgentFeed();
loadTracks();
