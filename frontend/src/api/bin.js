import ENV from '../env.js';
import { ApiError } from './notes.js';

// Unified bin (backend bin/FLOWS.md). Kinds: note folder file feed track track_item subscription unknown.
const BASE = `${ENV.API_BASE}/bin`;

async function req(url, options = {}) {
  const res = await fetch(url, { credentials: 'same-origin', cache: 'no-store', ...options });
  if (!res.ok) throw new ApiError(res.status);
  return res;
}

// → { note:[], folder:[], file:[], feed:[], track:[], track_item:[], subscription:[], unknown:[] }
// item: { kind, id (string), label, originalPath?, deletedAt }
export async function fetchBin() {
  return (await req(BASE)).json();
}

// → { restored:true, path? }. ApiError 404 gone · 409 subscription URL re-subscribed · 410 trashed copy gone.
export async function restoreBinItem(kind, id) {
  return (await req(`${BASE}/restore`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ kind, id }),
  })).json();
}

// 204.
export async function deleteBinItem(kind, id) {
  await req(`${BASE}/${encodeURIComponent(kind)}/${encodeURIComponent(id)}`, { method: 'DELETE' });
}

// → { removed:n }
export async function emptyBin() {
  return (await req(BASE, { method: 'DELETE' })).json();
}
