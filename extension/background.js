// MV3 service worker. All network calls live here so they run with the extension's
// host permissions (the page's own CORS/CSP/mixed-content rules don't apply) and so
// the ObsidianOptimizer session cookie is sent on same-origin terms.
//
// Reuses the exact backend contracts the web app uses (see frontend/src/api/notes.js):
//   GET  /me                          auth check
//   POST /login   (form-encoded)      session login
//   GET  /children                    vault root + folders
//   POST /notes   {folder, name}      create note → { path }
//   GET  /text?noteName=path          read note (to keep the sr-due frontmatter)
//   PUT  /notes   {path, content}     write note body
// And the offline downloader (yt-dlp in the embedder, proxied by the backend):
//   POST /download {url}              → { id, status, … }
//   GET  /download/{id}               → progress
import { getConfig, setConfig, api } from './config.js';

// ── Helpers ──────────────────────────────────────────────────────────────────
async function obsidian(path, opts = {}) {
  const { obsidianApi } = await getConfig();
  return fetch(`${obsidianApi}${path}`, { credentials: 'include', ...opts });
}

function sanitizeName(raw) {
  const cleaned = (raw || '')
    .replace(/[\\/:*?"<>|#^[\]]/g, ' ')  // illegal-in-filename + Obsidian-special
    .replace(/\s+/g, ' ')
    .trim()
    .slice(0, 80);
  return cleaned || `Web note ${new Date().toISOString().slice(0, 10)}`;
}

// ── Actions ──────────────────────────────────────────────────────────────────
async function checkAuth() {
  try {
    const res = await obsidian('/me');
    return { ok: res.ok };
  } catch (e) {
    return { ok: false, error: String(e) };
  }
}

async function login({ username, password }) {
  try {
    const body = new URLSearchParams({ username, password });
    const res = await obsidian('/login', { method: 'POST', body });
    return { ok: res.ok };
  } catch (e) {
    return { ok: false, error: String(e) };
  }
}

async function listFolders() {
  try {
    const res = await obsidian('/children');
    if (!res.ok) return { ok: false, status: res.status };
    const data = await res.json();
    const folders = (data.folderPaths || []).map(p => ({
      path: p,
      name: p.split(/[/\\]/).pop(),
    }));
    return { ok: true, root: data.parentPath, folders };
  } catch (e) {
    return { ok: false, error: String(e) };
  }
}

async function createNote({ title, body, folder, sourceUrl }) {
  try {
    // 1. Resolve the target folder (default = vault root).
    let targetFolder = folder;
    if (!targetFolder) {
      const res = await obsidian('/children');
      if (res.status === 401) return { ok: false, status: 401 };
      if (!res.ok) return { ok: false, status: res.status };
      targetFolder = (await res.json()).parentPath;
    }

    const name = sanitizeName(title);

    // 2. Create the note (backend writes the sr-due frontmatter + #review tag).
    const createRes = await obsidian('/notes', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ folder: targetFolder, name }),
    });
    if (createRes.status === 401) return { ok: false, status: 401 };
    if (!createRes.ok) return { ok: false, status: createRes.status, error: await createRes.text() };
    const { path } = await createRes.json();

    // 3. Read the just-created template so we keep its frontmatter intact.
    let template = '';
    try {
      const textRes = await obsidian(`/text?noteName=${encodeURIComponent(path)}`);
      if (textRes.ok) template = await textRes.text();
    } catch { /* fall back to empty — body still saved below */ }

    // 4. Append the clipped content and save.
    const parts = [template.replace(/\s*$/, ''), '', `# ${name}`];
    if (sourceUrl) parts.push('', `Source: ${sourceUrl}`);
    if (body && body.trim()) parts.push('', body.trim());
    const content = parts.join('\n') + '\n';

    const putRes = await obsidian('/notes', {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ path, content }),
    });
    if (!putRes.ok) return { ok: false, status: putRes.status, error: await putRes.text() };

    return { ok: true, path, name };
  } catch (e) {
    return { ok: false, error: String(e) };
  }
}

async function startDownload({ url }) {
  try {
    const res = await obsidian('/download', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ url }),
    });
    if (!res.ok) return { ok: false, status: res.status };
    const job = await res.json();           // embedder job: { id, status, … }
    return { ok: true, jobId: job.id };
  } catch (e) {
    return { ok: false, error: String(e) };
  }
}

async function downloadStatus({ jobId }) {
  try {
    const res = await obsidian(`/download/${jobId}`);
    if (!res.ok) return { ok: false, status: res.status };
    return { ok: true, job: await res.json() };
  } catch (e) {
    return { ok: false, error: String(e) };
  }
}

async function saveToWorkspace({ url }) {
  try {
    const res = await obsidian('/workspace/save', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ url }),
    });
    if (!res.ok) {
      const text = await res.text().catch(() => '');
      return { ok: false, status: res.status, error: text };
    }
    return { ok: true, ...(await res.json()) };
  } catch (e) {
    return { ok: false, error: String(e) };
  }
}

// ── Message router ────────────────────────────────────────────────────────────
const HANDLERS = {
  checkAuth: () => checkAuth(),
  login,
  listFolders: () => listFolders(),
  createNote,
  startDownload,
  downloadStatus,
  saveToWorkspace,
  getConfig: () => getConfig(),
  setConfig: (patch) => setConfig(patch).then(() => ({ ok: true })),
};

api.runtime.onMessage.addListener((msg, _sender, sendResponse) => {
  const handler = HANDLERS[msg?.type];
  if (!handler) {
    sendResponse({ ok: false, error: `unknown message: ${msg?.type}` });
    return false;
  }
  Promise.resolve(handler(msg.payload || {})).then(sendResponse);
  return true; // async response
});
