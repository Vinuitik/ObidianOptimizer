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
