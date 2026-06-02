const BASE = '/api';

export class ApiError extends Error {
  constructor(status) {
    super(`HTTP ${status}`);
    this.status = status;
  }
}

async function req(url, options = {}) {
  const res = await fetch(url, { credentials: 'same-origin', ...options });
  if (!res.ok) throw new ApiError(res.status);
  return res;
}

// ── Read (public) ────────────────────────────────────────────────────────────

export async function fetchNames() {
  const res = await fetch(`${BASE}/names`);
  if (!res.ok) throw new ApiError(res.status);
  return res.json();
}

export async function fetchReview() {
  const res = await fetch(`${BASE}/review`);
  if (!res.ok) throw new ApiError(res.status);
  return res.json();
}

export async function fetchNoteContent(fullPath) {
  const res = await fetch(`${BASE}/text?noteName=${encodeURIComponent(fullPath)}`);
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
