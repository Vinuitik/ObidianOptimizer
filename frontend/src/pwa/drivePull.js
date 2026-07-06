// Pull the offline set from Drive → IndexedDB, so review works with no server.
// Reads the ONE encrypted bundle the server exports (_offline/review-bundle.json.enc),
// not the 3k per-file vault notes — the server already narrowed it to due notes.
import { getCreds } from './setup';
import { getAccessToken, driveList, driveDownload } from './drive';
import { deriveKey, decryptText } from './crypto';
import { splitFrontmatter, parseFrontmatterFields } from '../utils/frontmatter';
import { putReviewNotes, putAssignments, setMeta } from './db';

const REVIEW_BUNDLE = 'review-bundle.json.enc';
const CARDS_BUNDLE = 'cards.json.enc';

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
export async function pullReviewFromDrive() {
  const creds = await getCreds();
  if (!creds) throw new Error('Link this device first (Drive link, below).');
  if (!creds.driveFolderId) throw new Error('No Drive folder yet — run a sync on the server first.');

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

  // Best-effort: pull the pre-built flashcard assignments too (offline card tests).
  // Absent bundle → self-rated review still works, so never fail the whole pull on it.
  let cards = 0;
  try {
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

  await setMeta('lastSync', Date.now());
  await setMeta('driveSource', true);
  return { notes: records.length, cards, generatedAt: bundle.generatedAt };
}

// Ask the server (while it's up) to rebuild the bundle, then pull it. Used at home so the
// train set is fresh; falls back to pulling the existing bundle if the server is off.
export async function refreshAndPull() {
  try {
    await fetch('/api/pwa/export', { method: 'POST', credentials: 'same-origin' });
  } catch { /* server off — pull whatever bundle is already on Drive */ }
  return pullReviewFromDrive();
}
