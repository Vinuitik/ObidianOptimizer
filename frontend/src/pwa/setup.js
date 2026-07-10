// Install handshake + P1 proof. linkDevice() pulls the server's Drive credentials once
// (over the tunnel, session-authed) into IndexedDB; after that the PWA is server-
// independent. proofReadNote() is the P1 sanity check: list → download → decrypt one real
// note straight from Drive, proving the same-client OAuth + WebCrypto path end to end.
import { setMeta, getMeta } from './db';
import { getAccessToken, driveFindFirstFile, driveDownload } from './drive';
import { deriveKey, decryptText } from './crypto';

const CREDS_KEY = 'driveCreds';

export async function linkDevice() {
  const res = await fetch('/api/pwa/setup', { credentials: 'same-origin' });
  if (res.status === 409) throw new Error(await res.text());
  if (res.status === 401) throw new Error('Sign in first, then link this device.');
  if (!res.ok) throw new Error(`Setup failed (${res.status})`);
  const creds = await res.json();
  await setMeta(CREDS_KEY, creds);
  return creds;
}

export const getCreds  = () => getMeta(CREDS_KEY);
export const hasCreds  = async () => Boolean(await getMeta(CREDS_KEY));
export const unlinkDevice = () => setMeta(CREDS_KEY, null);

// Re-pull the server's Drive credentials and MERGE the fresh fields into the stored blob.
// linkDevice() is a one-time snapshot; if the device linked BEFORE the server finished Drive
// setup it cached a blank driveFolderId (the folder id is only created+persisted on the
// server's first upload), and a rotated refresh token would go stale too. This heals both.
// Never clobbers a working blob when the server is unreachable or the session lapsed — it
// only overwrites on a clean 200 (a 401/409/offline keeps whatever we already have).
export async function refreshCreds() {
  const existing = await getCreds();
  if (!existing) return null;                       // not linked → nothing to refresh
  let res;
  try {
    res = await fetch('/api/pwa/setup', { credentials: 'same-origin' });
  } catch { return existing; }                      // offline / server down → keep current
  if (!res.ok) return existing;                     // 401 (signed out) / 409 (server not ready)
  const fresh = await res.json();
  const merged = { ...existing, ...fresh };
  await setMeta(CREDS_KEY, merged);
  return merged;
}

// P1 proof: prove we can read the vault from Drive with only the stored credentials.
export async function proofReadNote() {
  let creds = await getCreds();
  if (!creds) throw new Error('Link this device first.');
  if (!creds.driveFolderId && navigator.onLine) creds = (await refreshCreds()) || creds;
  if (!creds.driveFolderId) throw new Error('No Drive folder id — run a sync on the server first.');
  const token = await getAccessToken(creds);
  // Bounded first-match search (not a full-tree walk) so the test returns in a couple calls.
  const noteFile = await driveFindFirstFile(token, creds.driveFolderId, f => f.name.endsWith('.md.enc'));
  if (!noteFile) throw new Error('No .md.enc notes found on Drive.');
  const bytes = await driveDownload(token, noteFile.id);
  const key = await deriveKey(creds.passphrase);
  const text = await decryptText(key, bytes);
  return {
    path: noteFile.appProperties?.vault_path || noteFile.name,
    preview: text.slice(0, 240),
  };
}
