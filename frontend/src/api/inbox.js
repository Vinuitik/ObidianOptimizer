import ENV from '../env.js';

const BASE = ENV.API_BASE;

// Returns [{ path, title, source, suggestedFolder, content, captureId, captureSeq,
// captureSeqMinor, inPlace }, ...] — everything the ingest agent touched. captureSeqMinor
// is the manual-split sub-order (0 = original). Two shapes share the queue:
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

// Split a note into an additional sibling for the SAME source region (e.g. one PDF
// page range that is really two chapters). Creates a duplicate stamped with the next
// capture-seq-minor so it slots in right after the original (#N → #N-1) without
// renumbering anything else. Returns { path, captureSeqMinor }. Online-only.
export async function splitInboxNote(path) {
  const res = await fetch(`${BASE}/inbox/split`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    credentials: 'same-origin',
    body: JSON.stringify({ path }),
  });
  if (!res.ok) throw new Error(await res.text() || res.status);
  return res.json();
}

// Create an empty mini-folder directly under _inbox (a single flat segment, never nested,
// never outside the inbox). Returns { path, folder }. Online-only — a staging reorg, not
// something the offline queue replays. See InboxController.createFolder.
export async function createInboxFolder(name) {
  const res = await fetch(`${BASE}/inbox/folder`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    credentials: 'same-origin',
    body: JSON.stringify({ name }),
  });
  if (!res.ok) throw new Error(await res.text() || res.status);
  return res.json();
}

// Move a staged note between _inbox subfolders (drag-drop reorg). `folder` is a subfolder
// RELATIVE to _inbox; "" moves the note back to _inbox root. The note stays in the inbox
// (still excluded from FSRS review) — this is NOT filing into the real vault. Returns { path }.
export async function moveInboxNote(path, folder) {
  const res = await fetch(`${BASE}/inbox/move`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    credentials: 'same-origin',
    body: JSON.stringify({ path, folder }),
  });
  if (!res.ok) throw new Error(await res.text() || res.status);
  return res.json();
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
