// Cross-device account prefs (currently just rsvpWpm) via a single mutable file on Drive:
// _prefs/settings.json.enc. Browser-owned end to end — written/read directly via the same
// same-client OAuth + WebCrypto path proved by setup.js's proofReadNote(), no backend
// involvement. Best-effort throughout: a Drive hiccup never blocks the local (memory +
// localStorage) value, which is what actually drives the UI — this just keeps other
// devices' copy of that value current when reachable.
import { getCreds } from './setup';
import { getAccessToken, driveList, driveCreateFile, driveUpdateFile, driveDownload, findOrCreateFolder } from './drive';
import { deriveKey, encryptText, decryptText } from './crypto';

const PREFS_FOLDER = '_prefs';
const PREFS_FILE = 'settings.json.enc';

async function findPrefsFile(token, folderId) {
  const hits = await driveList(
    token,
    `name='${PREFS_FILE}' and '${folderId}' in parents and trashed=false`,
    { fields: 'files(id,name)' },
  );
  return hits[0] || null;
}

// null when not Drive-linked, unreachable, or nothing written yet — callers just keep
// whatever local default they already have.
export async function readPrefs() {
  try {
    const creds = await getCreds();
    if (!creds?.driveFolderId) return null;
    const token = await getAccessToken(creds);
    const folderId = await findOrCreateFolder(token, PREFS_FOLDER, creds.driveFolderId);
    const file = await findPrefsFile(token, folderId);
    if (!file) return null;
    const key = await deriveKey(creds.passphrase);
    return JSON.parse(await decryptText(key, await driveDownload(token, file.id)));
  } catch { return null; }
}

// Merges `patch` into whatever's already on Drive (so changing one field never clobbers
// another device's changes to a different one) and upserts — overwrites the existing file
// in place via driveUpdateFile if it exists, otherwise creates it once.
export async function writePrefs(patch) {
  try {
    const creds = await getCreds();
    if (!creds?.driveFolderId) return;
    const token = await getAccessToken(creds);
    const folderId = await findOrCreateFolder(token, PREFS_FOLDER, creds.driveFolderId);
    const file = await findPrefsFile(token, folderId);
    const key = await deriveKey(creds.passphrase);
    let current = {};
    if (file) {
      try { current = JSON.parse(await decryptText(key, await driveDownload(token, file.id))); }
      catch { /* corrupt/unreadable — overwrite with just this patch */ }
    }
    const enc = await encryptText(key, JSON.stringify({ ...current, ...patch }));
    if (file) await driveUpdateFile(token, file.id, enc);
    else await driveCreateFile(token, { name: PREFS_FILE, parents: [folderId] }, enc);
  } catch { /* best-effort — the local value is already saved regardless */ }
}
