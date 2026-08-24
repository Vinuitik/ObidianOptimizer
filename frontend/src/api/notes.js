import ENV from '../env.js';

const BASE = ENV.API_BASE;

export class ApiError extends Error {
  constructor(status) {
    super(`HTTP ${status}`);
    this.status = status;
  }
}

// CACHE DISABLED: remove cache:'no-store' when re-enabling caching
async function req(url, options = {}) {
  const res = await fetch(url, { credentials: 'same-origin', cache: 'no-store', ...options });
  if (!res.ok) throw new ApiError(res.status);
  return res;
}

// ── Read (public) ────────────────────────────────────────────────────────────

export async function fetchNames() {
  const res = await fetch(`${BASE}/names`, { cache: 'no-store' });
  if (!res.ok) throw new ApiError(res.status);
  return res.json();
}

// A utility folder the UI hides — any folder whose name begins with '_' (e.g. _inbox,
// _workspace). View-only: the backend still uses these; this just declutters folder lists.
export function isHiddenFolder(path) {
  const name = (path || '').replace(/[/\\]+$/, '').split(/[/\\]/).pop() || '';
  return name.startsWith('_');
}

// Returns { parentPath, folderPaths, filePaths }.
// folder=null fetches vault root. Underscore-prefixed utility folders are filtered out of
// folderPaths here (single chokepoint → hidden everywhere: tree, pickers, settings).
export async function fetchChildren(folder) {
  const url = folder
    ? `${BASE}/children?folder=${encodeURIComponent(folder)}`
    : `${BASE}/children`;
  const res = await fetch(url, { credentials: 'same-origin', cache: 'no-store' });
  if (!res.ok) throw new ApiError(res.status);
  const data = await res.json();
  if (Array.isArray(data.folderPaths)) {
    data.folderPaths = data.folderPaths.filter(p => !isHiddenFolder(p));
  }
  return data;
}

// Returns { notes: string[], hasMore: boolean }.
export async function fetchReview(offset = 0, limit = 40) {
  const res = await fetch(`${BASE}/review?offset=${offset}&limit=${limit}`, { credentials: 'same-origin', cache: 'no-store' });
  if (!res.ok) throw new ApiError(res.status);
  return res.json();
}

export async function fetchNoteContent(fullPath) {
  const res = await fetch(`${BASE}/text?noteName=${encodeURIComponent(fullPath)}`, { cache: 'no-store' });
  if (!res.ok) throw new ApiError(res.status);
  return res.text();
}

// ── Auth ─────────────────────────────────────────────────────────────────────

// Returns the raw HTTP status so callers can tell "really signed out" (401/403)
// apart from "server errored" (5xx / gateway errors) — those must not be treated
// the same, see useStore.js checkAuth().
export async function checkAuth() {
  const res = await fetch(`${BASE}/me`, { credentials: 'same-origin' });
  return res.status;
}

export async function login(username, password) {
  const body = new URLSearchParams({ username, password });
  const res = await fetch(`${BASE}/login`, { method: 'POST', body, credentials: 'same-origin' });
  return res.ok;
}

export async function logout() {
  await fetch(`${BASE}/logout`, { method: 'POST', credentials: 'same-origin' });
}

// ── Write (require auth) ─────────────────────────────────────────────────────

export async function createNote(folder, name) {
  const res = await req(`${BASE}/notes`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ folder, name }),
  });
  return res.json();
}

export async function updateNote(path, content) {
  await req(`${BASE}/notes`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ path, content }),
  });
}

export async function patchNote(path, hunks) {
  await req(`${BASE}/notes/content`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ path, hunks }),
  });
}

export async function renameNote(oldPath, newName) {
  const res = await req(`${BASE}/notes/rename`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ oldPath, newName }),
  });
  return res.json();
}

export async function deleteNote(path) {
  await req(`${BASE}/notes`, {
    method: 'DELETE',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ path }),
  });
}

export async function createFolder(parentPath, name) {
  const res = await req(`${BASE}/folders`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ parentPath, name }),
  });
  return res.json();
}

// Moves a folder AND all its contents to _trash/ (recursive soft-delete).
export async function deleteFolder(path) {
  await req(`${BASE}/folders`, {
    method: 'DELETE',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ path }),
  });
}

export async function moveNote(sourcePath, targetFolder) {
  const res = await req(`${BASE}/notes/move`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ sourcePath, targetFolder }),
  });
  return res.json();
}

// ── Settings ─────────────────────────────────────────────────────────────────

// Returns { vaultPath, resourcePath, reviewPageSize, startupSyncMode, maxDailyReviews, bankruptcyLimit }.
export async function fetchSettings() {
  const res = await fetch(`${BASE}/settings`, { cache: 'no-store' });
  if (!res.ok) throw new ApiError(res.status);
  return res.json();
}

// Partial update — only include the keys you want to change.
export async function saveSettings(patch) {
  const res = await req(`${BASE}/settings`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(patch),
  });
  return res.json();
}

// ── Host filesystem (for the vault folder picker) ────────────────────────────
// These proxy the host-wrapper (runs outside Docker) so the UI can browse the
// real machine's drives and switch which folder is mounted as the vault.

// Returns { path, parent, dirs: [{ path, name }] }. path=null/'' lists drive roots.
export async function fetchHostChildren(path) {
  const url = path
    ? `${BASE}/host/fs?path=${encodeURIComponent(path)}`
    : `${BASE}/host/fs`;
  const res = await fetch(url, { credentials: 'same-origin', cache: 'no-store' });
  if (!res.ok) throw new ApiError(res.status);
  return res.json();
}

// Returns { path } — the current HOST_VAULT_PATH from .env.
export async function fetchVaultHostPath() {
  const res = await fetch(`${BASE}/host/vault`, { credentials: 'same-origin', cache: 'no-store' });
  if (!res.ok) throw new ApiError(res.status);
  return res.json();
}

// Writes HOST_VAULT_PATH to .env and triggers a detached `docker compose up -d`
// to recreate the mounted services. Returns { path, recreating }.
export async function saveVaultHostPath(path) {
  const res = await req(`${BASE}/host/vault`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ path }),
  });
  return res.json();
}

// Liveness probe used while the backend is being recreated. "Up" means the
// backend itself answered — a 401/403 (session lost on container recreate) still
// counts, but a network error or nginx 502/503/504 (backend not yet listening)
// means it's still down.
export async function pingHealth() {
  try {
    const res = await fetch(`${BASE}/settings`, { cache: 'no-store' });
    return res.ok || res.status === 401 || res.status === 403;
  } catch {
    return false;
  }
}

// ── Upload ───────────────────────────────────────────────────────────────────

// Returns { filename, url }.
export async function uploadFile(file, filename) {
  const fd = new FormData();
  fd.append('file', file);
  fd.append('filename', filename);
  const res = await req(`${BASE}/upload`, { method: 'POST', body: fd });
  return res.json();
}

// ── Search ────────────────────────────────────────────────────────────────────

// Returns [{notePath, snippet, score}, ...].
// Pass { signal: AbortController.signal } to cancel in-flight requests.
export async function searchNotes(query, { signal } = {}) {
  const res = await fetch(
    `${BASE}/search?q=${encodeURIComponent(query)}&limit=10`,
    { credentials: 'same-origin', cache: 'no-store', signal }
  );
  if (!res.ok) throw new ApiError(res.status);
  return res.json();
}

// ── Reviews (FSRS + bandit) ──────────────────────────────────────────────────

// Returns [{note_path, stability, difficulty, reps, last_review, due}, ...].
export async function fetchDueReviews(limit = 50) {
  const res = await fetch(`${BASE}/reviews/due?limit=${limit}`, { credentials: 'same-origin', cache: 'no-store' });
  if (!res.ok) throw new ApiError(res.status);
  return res.json();
}

// band: 'HARD' | 'GOOD' | 'EASY' | 'VERY_EASY'.
// Returns {notePath, band, stability, difficulty, baseIntervalDays, banditArm, due}.
export async function gradeNote(notePath, band) {
  const res = await req(`${BASE}/reviews/grade`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ notePath, band }),
  });
  return res.json();
}

// ── Flashcard assignments ────────────────────────────────────────────────────

// Returns {id, scope, targetPoints, actualPoints, cards[], variants{}}.
export async function buildAssignment(scope, points) {
  const res = await req(`${BASE}/assignments`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ scope, points }),
  });
  return res.json();
}

// Returns {verdict, pointsEarned, maxPoints, feedback?}.
export async function submitAttempt(assignmentId, cardId, answer) {
  const res = await req(`${BASE}/attempts`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ assignmentId, cardId, answer }),
  });
  return res.json();
}

// Returns {assignmentId, notes: [{notePath, score, band, due}]}.
export async function completeAssignment(assignmentId) {
  const res = await req(`${BASE}/assignments/${assignmentId}/complete`, { method: 'POST' });
  return res.json();
}

// Flag a bad card. reason: optional user note on WHY it's bad (fed to the nightly
// regen agent). Quarantines the card out of the draw pool immediately.
export async function flagCard(cardId, reason) {
  await req(`${BASE}/cards/${cardId}/flag`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ reason: reason ?? '' }),
  });
}

// ── Chrono ────────────────────────────────────────────────────────────────────

// Returns { lastRunDate: string }.
export async function fetchChronoStatus() {
  const res = await fetch(`${BASE}/chrono/status`, { cache: 'no-store' });
  if (!res.ok) throw new ApiError(res.status);
  return res.json();
}

// Triggers all daily jobs immediately. Returns ChronoResult.
export async function runChronoNow() {
  const res = await req(`${BASE}/chrono/run`, { method: 'POST' });
  return res.json();
}
