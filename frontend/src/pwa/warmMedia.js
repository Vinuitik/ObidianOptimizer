// Warm the offline media cache DIRECTLY from the server (no Drive middleman for heavy blobs —
// the warm only ever runs while online/server-up, so Drive added nothing). Scope = the media
// referenced by the pulled review notes + the Learn inbox. Retention: evict cached media that
// falls out of that scope, so the phone store stays lean (user's "keep the phone clean").
//
// Best-effort: never throws — a failed warm just leaves the last-good cache in place. Call
// from the ONLINE sync path (drivePull.refreshAndPull) after the note set lands in IndexedDB.
import { getAllReviewNotes, getMeta } from './db';
import { mediaEntriesForNote } from '../utils/noteMedia';

const MEDIA_CACHE = 'obsopt-media';   // must match public/sw.js + syncOffline.js
// Only prune URLs the warm itself manages — never touch app icons or anything else the SW
// may have parked in this cache.
const MANAGED_RE = /^\/(vault-media|workspace|api\/images)\//;

// Full-quality download for now (user has space); server-side renditions are a later lever.
export async function warmReviewMedia({ onProgress } = {}) {
  if (!('caches' in self)) return { warmed: 0, evicted: 0, wanted: 0 };

  const notes = (await getAllReviewNotes().catch(() => [])) || [];
  const inbox = (await getMeta('inboxItems').catch(() => [])) || [];

  // In-scope media as {key, fetch} entries, deduped by canonical key (the URL the player uses
  // and the cache is keyed by). `key` drives retention; `fetch` is the rendition we download.
  const entries = new Map();
  for (const n of notes) for (const e of mediaEntriesForNote(n.content)) entries.set(e.key, e);
  for (const it of inbox) for (const e of mediaEntriesForNote(it.content)) entries.set(e.key, e);
  const wanted = new Set(entries.keys());

  const cache = await caches.open(MEDIA_CACHE);
  const present = new Set((await cache.keys()).map(r => new URL(r.url).pathname));

  // 1. Fetch what's wanted but not yet cached — the rendition (`fetch`), stored under the
  //    canonical player URL (`key`) so playback is unchanged. One-by-one so a single 404 /
  //    slow transcode doesn't abort the batch.
  const missing = [...entries.values()].filter(e => !present.has(e.key));
  let warmed = 0;
  for (let i = 0; i < missing.length; i++) {
    try {
      const res = await fetch(missing[i].fetch);
      if (res.ok) { await cache.put(missing[i].key, res); warmed++; }
    } catch { /* skip unreachable/oversized/failed transcode */ }
    onProgress?.({ done: i + 1, total: missing.length });
  }

  // 2. Retention: evict managed media that's no longer in scope.
  let evicted = 0;
  for (const req of await cache.keys()) {
    const path = new URL(req.url).pathname;
    if (MANAGED_RE.test(path) && !wanted.has(path)) {
      if (await cache.delete(req)) evicted++;
    }
  }

  return { warmed, evicted, wanted: wanted.size };
}
