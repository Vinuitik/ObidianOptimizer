// Pull the offline set from Drive → IndexedDB, so review works with no server.
// Reads the ONE encrypted bundle the server exports (_offline/review-bundle.json.enc),
// not the 3k per-file vault notes — the server already narrowed it to due notes.
import { getCreds, refreshCreds } from './setup';
import { getAccessToken, driveList, driveDownload } from './drive';
import { deriveKey, decryptText } from './crypto';
import { splitFrontmatter, parseFrontmatterFields } from '../utils/frontmatter';
import { putReviewNotes, putAssignments, putNoteVectors, setMeta } from './db';
import { fetchNoteVectors } from '../api/notes';

const REVIEW_BUNDLE = 'review-bundle.json.enc';
const CARDS_BUNDLE = 'cards.json.enc';
const INBOX_BUNDLE = 'inbox.json.enc';

// sr-due lives in the note's own frontmatter → the phone can compute due-ness itself.
function dueOf(content) {
  try {
    const { frontmatter } = splitFrontmatter(content);
    const field = parseFrontmatterFields(frontmatter).find(f => f.key === 'sr-due');
    return field?.value || null;
  } catch { return null; }
}

async function findOfflineFile(token, folderId, name) {
  const folders = await driveList(
    token,
    `mimeType='application/vnd.google-apps.folder' and name='_offline' and '${folderId}' in parents and trashed=false`,
    { fields: 'files(id,name)' });
  if (!folders.length) return null;
  const files = await driveList(
    token,
    `'${folders[0].id}' in parents and name='${name}' and trashed=false`,
    { fields: 'files(id,name,modifiedTime)' });
  return files[0] || null;
}

// Download + decrypt the review bundle into the reviewNotes store. Returns {notes, generatedAt}.
// onStage?.({ stage }) fires as each bundle phase starts so the UI can name what's downloading.
export async function pullReviewFromDrive({ onStage } = {}) {
  let creds = await getCreds();
  if (!creds) throw new Error('Link this device first (Drive link, below).');
  // Self-heal a device that linked too early (blank folder id cached): re-read the server
  // creds once before giving up — no manual unlink/re-link needed.
  if (!creds.driveFolderId && navigator.onLine) creds = (await refreshCreds()) || creds;
  if (!creds.driveFolderId) throw new Error('No Drive folder yet — run a sync on the server first.');

  onStage?.({ stage: 'notes' });
  const token = await getAccessToken(creds);
  const file = await findOfflineFile(token, creds.driveFolderId, REVIEW_BUNDLE);
  if (!file) throw new Error('No offline set on Drive yet — tap “Prep on server”, or open the desktop once.');

  const key = await deriveKey(creds.passphrase);
  const json = await decryptText(key, await driveDownload(token, file.id));
  const bundle = JSON.parse(json);

  const records = (bundle.notes || []).map(n => ({
    path: n.path,
    shortName: n.shortName,
    content: n.content,
    srDue: dueOf(n.content),
  }));
  await putReviewNotes(records);

  // Cache the review caps the bundle carried so the offline hybrid split matches the
  // desktop (the store reads this in Drive mode — see useStore.fetchReviewNotes).
  if (bundle.settings) await setMeta('reviewCaps', bundle.settings);

  // Best-effort: pull the pre-built flashcard assignments too (offline card tests).
  // Absent bundle → self-rated review still works, so never fail the whole pull on it.
  let cards = 0;
  try {
    onStage?.({ stage: 'cards' });
    const cardsFile = await findOfflineFile(token, creds.driveFolderId, CARDS_BUNDLE);
    if (cardsFile) {
      const cbundle = JSON.parse(await decryptText(key, await driveDownload(token, cardsFile.id)));
      const assignments = (cbundle.assignments || []).map(a => ({
        notePath: a.notePath,
        assignmentId: a.assignmentId,
        cards: a.cards,
        variants: a.variants,
      }));
      await putAssignments(assignments);
      cards = assignments.length;
    }
  } catch { /* keep self-rated review working */ }

  // Best-effort: pull the Learn inbox too (offline triage). Stored in meta as a plain
  // array; file/discard/acknowledge mutate it optimistically and queue mailbox events.
  let inbox = 0;
  try {
    onStage?.({ stage: 'inbox' });
    const inboxFile = await findOfflineFile(token, creds.driveFolderId, INBOX_BUNDLE);
    if (inboxFile) {
      const ib = JSON.parse(await decryptText(key, await driveDownload(token, inboxFile.id)));
      await setMeta('inboxItems', ib.items || []);
      inbox = (ib.items || []).length;
    }
  } catch { /* Learn offline is optional */ }

  await setMeta('lastSync', Date.now());
  await setMeta('driveSource', true);
  // paths: exposed so refreshAndPull can piggyback the vector-cache fetch (needs the
  // server, so it can't run in this Drive-only function — see refreshAndPull below).
  return { notes: records.length, cards, inbox, generatedAt: bundle.generatedAt, paths: records.map(r => r.path) };
}

// Ask the server (while it's up) to rebuild the bundle, then pull it. Used at home so the
// train set is fresh; falls back to pulling the existing bundle if the server is off.
// onStage?.({ stage, done, total }) reports progress phase-by-phase for the UI.
export async function refreshAndPull({ onStage } = {}) {
  let serverUp = false;
  try {
    await fetch('/api/pwa/export', { method: 'POST', credentials: 'same-origin' });
    serverUp = true;
  } catch { /* server off — pull whatever bundle is already on Drive */ }
  const res = await pullReviewFromDrive({ onStage });
  // Heavy media (images + A/V) comes DIRECT from the server, not Drive — only possible while
  // it's up. Best-effort: a failed warm never fails the pull. Scoped + self-evicting inside.
  if (serverUp) {
    try {
      const { warmReviewMedia } = await import('./warmMedia');
      res.media = await warmReviewMedia({
        onProgress: p => onStage?.({ stage: p.phase, done: p.done, total: p.total }),
      });
    } catch { /* keep the note set even if media warming fails */ }

    // Piggyback: cache embedding vectors for offline use (db.js 'noteVectors'). Same
    // reasoning as media above — the vector endpoint is server-direct, not on Drive, so
    // this can only run while the server answered. Best-effort; drift accepted otherwise.
    try {
      const vectors = await fetchNoteVectors(res.paths);
      await putNoteVectors(vectors);
    } catch { /* offline vector cache is best-effort */ }
  }
  return res;
}
