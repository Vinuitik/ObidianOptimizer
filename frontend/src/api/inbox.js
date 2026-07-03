import ENV from '../env.js';

const BASE = ENV.API_BASE;

// Returns [{ path, title, source, suggestedFolder, content, captureId, captureSeq,
// inPlace }, ...] — everything the ingest agent touched. Two shapes share the queue:
//   inPlace=false → a new note staged in _inbox/; file it into a real folder.
//   inPlace=true  → an existing note rewritten below an embed; acknowledge it.
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

// Acknowledge an in-place note (rewritten below an embed, never left its folder).
// Nothing to move — clears it from the queue and soft-deletes the capture's
// pre-rewrite source snapshot to _trash. Save any edits with updateNote first.
export async function acknowledgeCapture(captureId) {
  const res = await fetch(`${BASE}/inbox/acknowledge`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    credentials: 'same-origin',
    body: JSON.stringify({ captureId }),
  });
  if (!res.ok) throw new Error(await res.text() || res.status);
}
