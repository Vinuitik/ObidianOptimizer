// Pull the offline set from Drive → IndexedDB, so review works with no server.
// Reads the ONE encrypted bundle the server exports (_offline/review-bundle.json.enc),
// not the 3k per-file vault notes — the server already narrowed it to due notes.
import { getCreds, refreshCreds } from './setup';
import { getAccessToken, driveList, driveDownload } from './drive';
import { deriveKey, decryptText } from './crypto';
import { splitFrontmatter, parseFrontmatterFields } from '../utils/frontmatter';
import { putReviewNotes, putAssignments, setMeta, getMeta, getAllReviewNotes } from './db';
import { fetchNames } from '../api/notes';

const REVIEW_BUNDLE = 'review-bundle.json.enc';
const CARDS_BUNDLE = 'cards.json.enc';
const INBOX_BUNDLE = 'inbox.json.enc';

// Once today's review LIST is set, a background pull (autoSync, a reconnect) must not
// silently reshuffle or replace it — only cards/inbox refresh in the background; the
// note SET you're actually reviewing stays put until a new calendar day, or an explicit
// forced refresh (the manual "Download for offline" tap). This does NOT pin media
// warming — a connection drop mid-warm still needs to keep retrying for whatever's
// already on today's list, just not change WHICH notes are on it.
const REVIEW_LIST_DATE_KEY = 'reviewListDate';
const todayStr = () => new Date().toISOString().slice(0, 10);
async function reviewListAlreadyPreparedToday() {
  return (await getMeta(REVIEW_LIST_DATE_KEY).catch(() => null)) === todayStr();
}

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
// force=true (the manual "Download for offline" tap) bypasses the daily pin below; every
// other caller (autoSync, a reconnect) leaves it false and respects it.
export async function pullReviewFromDrive({ onStage, force = false } = {}) {
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
  // Skip overwriting the list itself if today's was already prepared — see the pin note
  // above. The bundle is still downloaded (cheap enough) so cards/inbox below still get
  // fresh, and so the caller can tell listPinned happened rather than silently no-op'ing.
  const listPinned = !force && await reviewListAlreadyPreparedToday();
  if (!listPinned) {
    await putReviewNotes(records);
    await setMeta(REVIEW_LIST_DATE_KEY, todayStr());
  }

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
  // notes reports what's actually ON the phone: the just-pulled count normally, or the
  // existing local count when the list was pinned (so a "0 notes" summary doesn't read as
  // a failed/empty pull when really nothing was supposed to change).
  const notes = listPinned ? (await getAllReviewNotes()).length : records.length;
  return { notes, cards, inbox, generatedAt: bundle.generatedAt, listPinned };
}

// Ask the server (while it's up) to rebuild the bundle, then pull it. Used at home so the
// train set is fresh; falls back to pulling the existing bundle if the server is off.
// onStage?.({ stage, done, total }) reports progress phase-by-phase for the UI. force is
// forwarded to pullReviewFromDrive — see its daily-pin note.
export async function refreshAndPull({ onStage, force = false } = {}) {
  let serverUp = false;
  try {
    await fetch('/api/pwa/export', { method: 'POST', credentials: 'same-origin' });
    serverUp = true;
  } catch { /* server off — pull whatever bundle is already on Drive */ }
  const res = await pullReviewFromDrive({ onStage, force });
  // Media warming always runs now, server up or not — warmReviewMedia falls back to
  // fetching images/plain files directly from Drive (same mirrored resources/ files the
  // vault sync already uploads) when the server-direct fetch is unavailable. Video/audio
  // and PDF pages still need the live server (transcoding/rendering happens there) — see
  // warmMedia.js. Best-effort either way: a failed warm never fails the pull.
  try {
    const { warmReviewMedia } = await import('./warmMedia');
    res.media = await warmReviewMedia({
      onProgress: p => onStage?.({ stage: p.phase, done: p.done, total: p.total }),
    });
  } catch { /* keep the note set even if media warming fails */ }

  if (serverUp) {
    // Piggyback: cache the full vault's note names for offline search/link fallback
    // (utils/offlineSearch.js). /names is server-direct, not on Drive, so this can only
    // run while the server answered. Best-effort; drift accepted otherwise.
    try {
      await setMeta('cachedNoteNames', await fetchNames());
    } catch { /* offline name cache is best-effort */ }
  }
  return res;
}
