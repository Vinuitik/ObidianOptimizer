// Google Drive REST client for the PWA. Authenticates as the SAME OAuth client as the
// server (credentials shared at install — see DRIVE_OFFLINE_SYNC_ARCH §5), so it sees the
// server-created .enc files under the drive.file scope. Server-independent after setup:
// it refreshes its own access token directly against Google.
//
// Requires (from IndexedDB `meta`, set by the install handshake):
//   clientId, clientSecret, refreshToken, driveFolderId
//
// CORS/CSP note: this fetches oauth2.googleapis.com + www.googleapis.com cross-origin.
// The page CSP must allow them in connect-src, and Google must allow the browser origin
// on the token refresh grant — both verified in the P1 proof (fallback: server-proxied
// refresh). See the arch plan §5 / §11.

const TOKEN_URL = 'https://oauth2.googleapis.com/token';
const DRIVE_API = 'https://www.googleapis.com/drive/v3';
const UPLOAD_API = 'https://www.googleapis.com/upload/drive/v3';

let cachedToken = null;   // { access_token, expiresAt }

// Refresh-token grant → short-lived access token (cached in memory until ~1 min before expiry).
export async function getAccessToken(creds) {
  if (cachedToken && cachedToken.expiresAt - 60_000 > Date.now()) return cachedToken.access_token;
  const body = new URLSearchParams({
    client_id: creds.clientId,
    client_secret: creds.clientSecret,
    refresh_token: creds.refreshToken,
    grant_type: 'refresh_token',
  });
  const res = await fetch(TOKEN_URL, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body,
  });
  if (!res.ok) throw new Error(`token refresh ${res.status}: ${await res.text().catch(() => '')}`);
  const json = await res.json();
  cachedToken = { access_token: json.access_token, expiresAt: Date.now() + (json.expires_in ?? 3600) * 1000 };
  return cachedToken.access_token;
}

export function clearTokenCache() { cachedToken = null; }

async function driveFetch(token, url, init = {}) {
  const res = await fetch(url, { ...init, headers: { Authorization: `Bearer ${token}`, ...(init.headers || {}) } });
  if (!res.ok) throw new Error(`drive ${res.status}: ${await res.text().catch(() => '')}`);
  return res;
}

// List files matching a Drive query. Returns [{id, name, appProperties, mimeType, modifiedTime}].
export async function driveList(token, query, { fields = 'files(id,name,appProperties,mimeType,modifiedTime),nextPageToken' } = {}) {
  const out = [];
  let pageToken;
  do {
    const params = new URLSearchParams({ q: query, fields, pageSize: '1000', spaces: 'drive' });
    if (pageToken) params.set('pageToken', pageToken);
    const res = await driveFetch(token, `${DRIVE_API}/files?${params}`);
    const json = await res.json();
    out.push(...(json.files || []));
    pageToken = json.nextPageToken;
  } while (pageToken);
  return out;
}

// List everything under a folder subtree (BFS), returning file leaves with their
// appProperties (vault_path lets the caller map back to a note path).
export async function driveListTree(token, rootFolderId) {
  const files = [];
  let frontier = [rootFolderId];
  while (frontier.length) {
    const next = [];
    for (const folderId of frontier) {
      const children = await driveList(token, `'${folderId}' in parents and trashed=false`);
      for (const c of children) {
        if (c.mimeType === 'application/vnd.google-apps.folder') next.push(c.id);
        else files.push(c);
      }
    }
    frontier = next;
  }
  return files;
}

// Find the FIRST file matching `pred` under a folder subtree, descending depth-first so a
// match is hit in a handful of API calls instead of listing the whole tree. Used by the
// Drive "test read" — driveListTree over a multi-thousand-file mirror is hundreds of
// sequential calls (~40s); this returns after the first note it finds. null if none.
export async function driveFindFirstFile(token, rootFolderId, pred, depth = 0) {
  if (depth > 8) return null;
  const children = await driveList(token, `'${rootFolderId}' in parents and trashed=false`);
  const hit = children.find(c => c.mimeType !== 'application/vnd.google-apps.folder' && pred(c));
  if (hit) return hit;
  for (const c of children) {
    if (c.mimeType === 'application/vnd.google-apps.folder') {
      const found = await driveFindFirstFile(token, c.id, pred, depth + 1);
      if (found) return found;
    }
  }
  return null;
}

export async function driveDownload(token, fileId) {
  const res = await driveFetch(token, `${DRIVE_API}/files/${fileId}?alt=media`);
  return new Uint8Array(await res.arrayBuffer());
}

// Find (or create) a folder by name under a parent. Used for _mailbox/ writes.
export async function findOrCreateFolder(token, name, parentId) {
  const existing = await driveList(
    token,
    `name='${name}' and mimeType='application/vnd.google-apps.folder' and '${parentId}' in parents and trashed=false`,
    { fields: 'files(id,name)' },
  );
  if (existing.length) return existing[0].id;
  const res = await driveFetch(token, `${DRIVE_API}/files`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ name, mimeType: 'application/vnd.google-apps.folder', parents: [parentId] }),
  });
  return (await res.json()).id;
}

// Multipart upload: JSON metadata + raw bytes in one request. Used to drop encrypted
// event files into the _mailbox/ folder.
export async function driveCreateFile(token, { name, parents, appProperties }, bytes) {
  const boundary = 'obsopt-' + crypto.randomUUID();
  const meta = JSON.stringify({ name, parents, appProperties });
  const enc = new TextEncoder();
  const head = enc.encode(
    `--${boundary}\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n${meta}\r\n` +
    `--${boundary}\r\nContent-Type: application/octet-stream\r\n\r\n`);
  const tail = enc.encode(`\r\n--${boundary}--`);
  const body = new Uint8Array(head.length + bytes.length + tail.length);
  body.set(head, 0); body.set(bytes, head.length); body.set(tail, head.length + bytes.length);

  const res = await driveFetch(token, `${UPLOAD_API}/files?uploadType=multipart&fields=id`, {
    method: 'POST',
    headers: { 'Content-Type': `multipart/related; boundary=${boundary}` },
    body,
  });
  return (await res.json()).id;
}
