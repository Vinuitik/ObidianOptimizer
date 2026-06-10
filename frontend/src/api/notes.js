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

// Returns { parentPath, folderPaths, filePaths }.
// folder=null fetches vault root.
export async function fetchChildren(folder) {
  const url = folder
    ? `${BASE}/children?folder=${encodeURIComponent(folder)}`
    : `${BASE}/children`;
  const res = await fetch(url, { credentials: 'same-origin', cache: 'no-store' });
  if (!res.ok) throw new ApiError(res.status);
  return res.json();
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

export async function checkAuth() {
  const res = await fetch(`${BASE}/me`, { credentials: 'same-origin' });
  return res.ok;
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

// ── Settings ─────────────────────────────────────────────────────────────────

// Returns { vaultPath, resourcePath, reviewPageSize }.
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
