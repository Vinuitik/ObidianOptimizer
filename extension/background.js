// MV3 background (service worker in Chrome / event page in Firefox). All network
// calls live here so they run with the extension's host permissions (the page's
// own CORS/CSP/mixed-content rules don't apply) and the ObsidianOptimizer session
// cookie is sent on same-origin terms.
//
// One job: take whatever you capture (raw text, a URL, or an uploaded file) and
// hand it to the backend so it ends up reviewable in the Learn page. Routing is
// deliberately thin — the embedder's ingest router already classifies youtube /
// web / pdf / av, so for URLs we just POST /capture and let it decide.
//
// Backend contracts used:
//   GET  /me                          auth check
//   POST /login   (form-encoded)      session login
//   GET  /children                    vault root (default folder for raw-text notes)
//   POST /notes   {folder, name}      create note → { path }   (writes sr-due + #review)
//   PUT  /notes   {path, content}     write note body
//   POST /capture {url}               → embedder /ingest (extract → synthesize → inbox)
//   POST /workspace/save   {url}      download a media/pdf URL into _workspace/
//   POST /workspace/upload (multipart) store a dropped local file in _workspace/
//   POST /download {url}              yt-dlp offline grab → _workspace/ (watchable)
import { getConfig, setConfig, api } from './config.js';

// ── HTTP helper ────────────────────────────────────────────────────────────────
async function obsidian(path, opts = {}) {
  const { obsidianApi } = await getConfig();
  return fetch(`${obsidianApi}${path}`, { credentials: 'include', ...opts });
}

const json = (path, method, payload) => obsidian(path, {
  method,
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify(payload),
});

// ── Input classification ─────────────────────────────────────────────────────
// Video platforms where we want BOTH a watchable file (yt-dlp → _workspace) AND
// synthesized notes (ingest). Everything else that's a URL just goes to /capture.
const VIDEO_HOST_RE = /(?:^|\.)(youtube\.com|youtu\.be|vimeo\.com|dailymotion\.com)$/i;
const MEDIA_EXT_RE  = /\.(pdf|mp4|mov|mkv|webm|avi|mp3|m4a|wav|ogg|flac)(?:[?#]|$)/i;
const URL_RE        = /^https?:\/\/\S+$/i;

function classify(text) {
  const t = (text || '').trim();
  if (!URL_RE.test(t)) return { kind: 'text', value: t };
  let host = '';
  try { host = new URL(t).host; } catch { /* malformed → treat as text */ return { kind: 'text', value: t }; }
  if (VIDEO_HOST_RE.test(host)) return { kind: 'video', value: t };
  if (MEDIA_EXT_RE.test(t))     return { kind: 'media', value: t };
  return { kind: 'web', value: t };
}

function sanitizeName(raw) {
  const cleaned = (raw || '')
    .replace(/[\\/:*?"<>|#^[\]]/g, ' ')
    .replace(/\s+/g, ' ')
    .trim()
    .slice(0, 80);
  return cleaned || `Capture ${new Date().toISOString().slice(0, 10)}`;
}

// ── Auth + config ────────────────────────────────────────────────────────────
async function checkAuth() {
  try { return { ok: (await obsidian('/me')).ok }; }
  catch (e) { return { ok: false, error: String(e) }; }
}

async function login({ username, password }) {
  try {
    const res = await obsidian('/login', {
      method: 'POST',
      body: new URLSearchParams({ username, password }),
    });
    return { ok: res.ok, status: res.status };
  } catch (e) { return { ok: false, error: String(e) }; }
}

// ── Capture actions ────────────────────────────────────────────────────────────
// Raw text/markdown → a Capture: the original is kept as a resource and the ingest
// agent synthesizes proposed notes from it (CAPTURE_ARCH.md). The user amends/files
// them in the Learn queue — AI never auto-files.
async function captureText(text) {
  const firstLine = text.split('\n').find(l => l.trim()) || '';
  const title = sanitizeName(firstLine);
  const res = await json('/capture', 'POST', { text, title });
  if (res.status === 401) return { ok: false, status: 401 };
  if (!res.ok) return { ok: false, status: res.status, error: await res.text() };
  return { ok: true, kind: 'text', detail: `Synthesizing notes from “${title}”` };
}

// A URL → the ingest pipeline (extract → synthesize → Learn inbox).
async function capture(url) {
  const res = await json('/capture', 'POST', { url });
  if (res.status === 401) return { ok: false, status: 401 };
  if (!res.ok) return { ok: false, status: res.status, error: await res.text() };
  return { ok: true };
}

// Keep a watchable/readable copy in _workspace (media-file URL).
async function workspaceSave(url) {
  const res = await json('/workspace/save', 'POST', { url });
  if (res.status === 401) return { ok: false, status: 401 };
  if (!res.ok) return { ok: false, status: res.status, error: await res.text() };
  return { ok: true };
}

// Keep a watchable copy via yt-dlp (video-platform URL). Fire-and-forget: we don't
// poll the job — the file appears in Learn when it's done.
async function startDownload(url) {
  const res = await json('/download', 'POST', { url });
  if (res.status === 401) return { ok: false, status: 401 };
  if (!res.ok) return { ok: false, status: res.status, error: await res.text() };
  return { ok: true };
}

// Route a single captured string (used by both popup and context menu).
async function routeText(text) {
  const { kind, value } = classify(text);
  if (kind === 'text') return captureText(value);

  // For URLs, always generate notes via /capture. For media/video, also keep a
  // viewable copy in _workspace so it shows up in Learn's player.
  const captureRes = await capture(value);
  if (!captureRes.ok) return captureRes;

  if (kind === 'media') {
    const keep = await workspaceSave(value);
    return { ok: true, kind, detail: keep.ok
      ? 'Saved to workspace + generating notes' : 'Generating notes (file save failed)' };
  }
  if (kind === 'video') {
    const keep = await startDownload(value);
    return { ok: true, kind, detail: keep.ok
      ? 'Downloading for offline + generating notes' : 'Generating notes (download failed)' };
  }
  return { ok: true, kind, detail: 'Generating notes from the page' };
}

// A dropped local file → store in _workspace, then ingest it for notes.
async function uploadFile({ name, type, dataB64 }) {
  try {
    const bytes = Uint8Array.from(atob(dataB64), c => c.charCodeAt(0));
    const form = new FormData();
    form.append('file', new Blob([bytes], { type: type || 'application/octet-stream' }), name);
    const res = await obsidian('/workspace/upload', { method: 'POST', body: form });
    if (res.status === 401) return { ok: false, status: 401 };
    if (!res.ok) return { ok: false, status: res.status, error: await res.text() };
    const { path } = await res.json();
    // Ingest the stored file for notes (vault-relative path is a valid ingest ref).
    await capture(path).catch(() => {});
    return { ok: true, kind: 'file', detail: `Uploaded “${name}” + generating notes` };
  } catch (e) { return { ok: false, error: String(e) }; }
}

// ── Message router (popup → background) ─────────────────────────────────────────
const HANDLERS = {
  checkAuth: () => checkAuth(),
  login,
  routeText: ({ text }) => routeText(text),
  uploadFile,
  getConfig: () => getConfig(),
  setConfig: (patch) => setConfig(patch).then(() => ({ ok: true })),
};

api.runtime.onMessage.addListener((msg, _sender, sendResponse) => {
  const handler = HANDLERS[msg?.type];
  if (!handler) { sendResponse({ ok: false, error: `unknown message: ${msg?.type}` }); return false; }
  Promise.resolve(handler(msg.payload || {})).then(sendResponse);
  return true; // async response
});

// ── Context menu (right-click → Send to Learn) ──────────────────────────────────
const MENU = [
  { id: 'learn-selection', title: 'Send selection to Learn', contexts: ['selection'] },
  { id: 'learn-link',      title: 'Send link to Learn',      contexts: ['link'] },
  { id: 'learn-page',      title: 'Send this page to Learn', contexts: ['page'] },
];

function installMenus() {
  api.contextMenus.removeAll(() => MENU.forEach(m => api.contextMenus.create(m)));
}

async function flashBadge(ok) {
  try {
    await api.action.setBadgeBackgroundColor({ color: ok ? '#4cc38a' : '#e05c5c' });
    await api.action.setBadgeText({ text: ok ? '✓' : '!' });
    setTimeout(() => api.action.setBadgeText({ text: '' }), 2500);
  } catch { /* badge is best-effort */ }
}

api.contextMenus.onClicked.addListener(async (info) => {
  const text = info.menuItemId === 'learn-selection' ? info.selectionText
             : info.menuItemId === 'learn-link'      ? info.linkUrl
             : info.pageUrl;
  if (!text) return;
  const res = await routeText(text);
  flashBadge(res.ok);
});

// ── Install: create menus + open the onboarding tab once ────────────────────────
api.runtime.onInstalled.addListener((details) => {
  installMenus();
  if (details.reason === 'install') {
    api.tabs.create({ url: api.runtime.getURL('onboarding.html') });
  }
});

// Firefox event pages can be torn down; rebuild menus on startup too.
api.runtime.onStartup?.addListener(installMenus);
