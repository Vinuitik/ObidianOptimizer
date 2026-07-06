import ENV from '../env.js';

const BASE = ENV.API_BASE;

async function req(url, options = {}) {
  const res = await fetch(`${BASE}${url}`, {
    credentials: 'same-origin',
    cache: 'no-store',
    ...options,
  });
  if (!res.ok) {
    let msg = String(res.status);
    try { msg = (await res.json()).error ?? msg; } catch { /* not json */ }
    throw new Error(msg);
  }
  return res.json();
}

// { pending, done, failed, deviceId, enabled, encryptionConfigured, driveConfigured,
//   mode, clientConfigured, connected, accountEmail }
export function fetchSyncStatus() {
  return req('/sync/status');
}

// Google consent URL for "Connect Google Drive". The redirect URI is derived from
// this origin, so it must be registered on the OAuth client in Google Cloud Console.
export async function fetchOAuthUrl() {
  const { url } = await req(`/sync/oauth/url?origin=${encodeURIComponent(window.location.origin)}`);
  return url;
}

export function disconnectDrive() {
  return req('/sync/disconnect', { method: 'POST' });
}

export function triggerSyncUpload() {
  return req('/sync/upload', { method: 'POST' });
}

export function triggerSyncDownload() {
  return req('/sync/download', { method: 'POST' });
}

// Orphan sweep. dryRun=true only reports { scanned, orphans, freedBytes, deletedPaths };
// dryRun=false moves the orphans to Drive trash (30-day recovery).
export function runJanitor(dryRun = true) {
  return req(`/sync/janitor?dryRun=${dryRun}`, { method: 'POST' });
}

// DB backup: pg_dump → encrypt → Drive _db/. Returns immediately (202); poll status.
export function triggerDbBackup() {
  return req('/sync/db/backup', { method: 'POST' });
}

// Full Backup: upload pending vault files THEN dump the DB. Returns immediately (202).
export function triggerFullBackup() {
  return req('/sync/backup', { method: 'POST' });
}

// DB restore: pull latest dump + materialize vault files. force=true overwrites a
// non-empty DB (destructive — confirm first). 400 with {error} if blocked.
export function triggerDbRestore(force = false) {
  return req(`/sync/db/restore?force=${force}`, { method: 'POST' });
}
