import ENV from '../env.js';

const BASE = ENV.API_BASE;

// Returns [{ path, title, source, suggestedFolder, content }, ...] — notes the
// ingest agent generated and parked in _workspace's sibling _inbox/ staging folder.
export async function fetchInbox() {
  const res = await fetch(`${BASE}/inbox`, { credentials: 'same-origin', cache: 'no-store' });
  if (!res.ok) throw new Error(res.status);
  return res.json();
}

// Save edits + move the note out of _inbox into a real folder (enters review).
// Returns { path } — the new location.
export async function fileInboxNote(path, targetFolder, content) {
  const res = await fetch(`${BASE}/inbox/file`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    credentials: 'same-origin',
    body: JSON.stringify({ path, targetFolder, content }),
  });
  if (!res.ok) throw new Error(await res.text() || res.status);
  return res.json();
}

// Discard a generated note (soft-delete to _trash).
export async function discardInboxNote(path) {
  const res = await fetch(`${BASE}/inbox`, {
    method: 'DELETE',
    headers: { 'Content-Type': 'application/json' },
    credentials: 'same-origin',
    body: JSON.stringify({ path }),
  });
  if (!res.ok) throw new Error(res.status);
}
