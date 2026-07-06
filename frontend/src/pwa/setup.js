// Install handshake + P1 proof. linkDevice() pulls the server's Drive credentials once
// (over the tunnel, session-authed) into IndexedDB; after that the PWA is server-
// independent. proofReadNote() is the P1 sanity check: list → download → decrypt one real
// note straight from Drive, proving the same-client OAuth + WebCrypto path end to end.
import { setMeta, getMeta } from './db';
import { getAccessToken, driveListTree, driveDownload } from './drive';
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

// P1 proof: prove we can read the vault from Drive with only the stored credentials.
export async function proofReadNote() {
  const creds = await getCreds();
  if (!creds) throw new Error('Link this device first.');
  if (!creds.driveFolderId) throw new Error('No Drive folder id — run a sync on the server first.');
  const token = await getAccessToken(creds);
  const files = await driveListTree(token, creds.driveFolderId);
  const noteFile = files.find(f => f.name.endsWith('.md.enc'));
  if (!noteFile) throw new Error('No .md.enc notes found on Drive.');
  const bytes = await driveDownload(token, noteFile.id);
  const key = await deriveKey(creds.passphrase);
  const text = await decryptText(key, bytes);
  return {
    path: noteFile.appProperties?.vault_path || noteFile.name,
    total: files.length,
    preview: text.slice(0, 240),
  };
}
