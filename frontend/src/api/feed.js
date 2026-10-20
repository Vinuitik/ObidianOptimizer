import ENV from '../env.js';
import { ApiError } from './notes.js';

const BASE = ENV.API_BASE;

const JSON_HDR = { 'Content-Type': 'application/json' };

async function req(url, options = {}) {
  const res = await fetch(url, { credentials: 'same-origin', cache: 'no-store', ...options });
  if (!res.ok) throw new ApiError(res.status);
  return res;
}

// ── Items (contract: tracks/FLOWS_feed.md P1a) ───────────────────────────────

// status: pending | binned | accepted. Newest published first.
// → [{ id, sourceId, sourceTitle, itemUrl, title, thumbUrl, durationS, publishedAt, status, decidedAt, captureId }]
export async function fetchFeedItems(status = 'pending', limit = 50) {
  const res = await req(`${BASE}/feed/items?status=${status}&limit=${limit}`);
  return res.json();
}

// 200 { captureId }, idempotent.
export async function acceptFeedItem(id) {
  const res = await req(`${BASE}/feed/items/${id}/accept`, { method: 'POST' });
  return res.json();
}

// 204, idempotent.
export async function binFeedItem(id) {
  await req(`${BASE}/feed/items/${id}/bin`, { method: 'POST' });
}

// 204 → pending. ApiError(409) when the item's capture already left QUEUED.
export async function restoreFeedItem(id) {
  await req(`${BASE}/feed/items/${id}/restore`, { method: 'POST' });
}

// ── Sources ──────────────────────────────────────────────────────────────────

// → [{ id, kind, url, feedUrl, title, autoAccept|null, status, lastCheckedAt, pendingCount }]
export async function fetchFeedSources() {
  const res = await req(`${BASE}/feed/sources`);
  return res.json();
}

export async function addFeedSource(url, extra = {}) {
  const res = await req(`${BASE}/feed/sources`, {
    method: 'POST', headers: JSON_HDR, body: JSON.stringify({ url, ...extra }),
  });
  return res.json();
}

// Preview only, nothing persisted. 422 = no feed readable from the URL.
// -> { kind, feedUrl, title, preview: [{ title, itemUrl, publishedAt, thumbUrl, durationS }] }
export async function resolveFeedSource(url) {
  const res = await req(`${BASE}/feed/sources/resolve`, {
    method: 'POST', headers: JSON_HDR, body: JSON.stringify({ url }),
  });
  return res.json();
}

// patch: any of { autoAccept (true|false|null=inherit), status, title }
export async function patchFeedSource(id, patch) {
  const res = await req(`${BASE}/feed/sources/${id}`, {
    method: 'PATCH', headers: JSON_HDR, body: JSON.stringify(patch),
  });
  return res.json();
}

export async function deleteFeedSource(id) {
  await req(`${BASE}/feed/sources/${id}`, { method: 'DELETE' });
}

// 202 — poll runs async; caller re-fetches items/sources afterwards.
export async function pollFeedSource(id) {
  await req(`${BASE}/feed/sources/${id}/poll`, { method: 'POST' });
}
