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
// may have parked in this cache. Tested against the PATHNAME (query stripped).
const MANAGED_RE = /^\/(vault-media|workspace|api\/images|pdf-page)(\/|$)/;

// Cache keys can carry a query string (/pdf-page?path=…&page=N), so identity is pathname+search,
// NOT pathname alone — otherwise every PDF page collapses to "/pdf-page" and retention nukes them.
const idOf = (urlStr) => { const u = new URL(urlStr); return u.pathname + u.search; };

// Which progress bucket a key belongs to (drives the "images 3/12 → PDF 1/4" UI label).
function phaseOf(key) {
  if (key.startsWith('/pdf-page')) return 'pdf';
  if (key.startsWith('/api/images/')) return 'images';
  return 'media';   // A/V renditions + any other source file
}

// Download EVERYTHING each in-scope note sources (images + A/V renditions + PDF pages + other
// files), keyed under the canonical URL the player requests. Best-effort: never throws.
export async function warmReviewMedia({ onProgress } = {}) {
  if (!('caches' in self)) return { warmed: 0, evicted: 0, wanted: 0, byPhase: {} };

  const notes = (await getAllReviewNotes().catch(() => [])) || [];
  const inbox = (await getMeta('inboxItems').catch(() => [])) || [];

  // In-scope media as {key, fetch} entries, deduped by canonical key (the URL the player uses
  // and the cache is keyed by). `key` drives retention; `fetch` is what we download.
  const entries = new Map();
  for (const n of notes) for (const e of mediaEntriesForNote(n.content, n.source)) entries.set(e.key, e);
  for (const it of inbox) for (const e of mediaEntriesForNote(it.content, it.source)) entries.set(e.key, e);
  const wanted = new Set(entries.keys());   // keys are already pathname(+search)

  const cache = await caches.open(MEDIA_CACHE);
  const present = new Set((await cache.keys()).map(r => idOf(r.url)));

  // 1. Fetch what's wanted but not yet cached, stored under the canonical player URL (`key`)
  //    so playback is unchanged. One-by-one so a single 404 / slow transcode doesn't abort the
  //    batch. Ordered by phase so the UI narrates images → video/audio → PDF distinctly.
  const missing = [...entries.values()].filter(e => !present.has(e.key));
  const groups = { images: [], media: [], pdf: [] };
  for (const e of missing) groups[phaseOf(e.key)].push(e);

  const byPhase = { images: 0, media: 0, pdf: 0 };
  let warmed = 0;
  for (const phase of ['images', 'media', 'pdf']) {
    const list = groups[phase];
    for (let i = 0; i < list.length; i++) {
      try {
        const res = await fetch(list[i].fetch);
        if (res.ok) { await cache.put(list[i].key, res); warmed++; byPhase[phase]++; }
      } catch { /* skip unreachable/oversized/failed transcode */ }
      onProgress?.({ phase, done: i + 1, total: list.length });
    }
  }

  // 2. Retention: evict managed media that's no longer in scope.
  let evicted = 0;
  for (const req of await cache.keys()) {
    const id = idOf(req.url);
    if (MANAGED_RE.test(new URL(req.url).pathname) && !wanted.has(id)) {
      if (await cache.delete(req)) evicted++;
    }
  }

  return { warmed, evicted, wanted: wanted.size, byPhase };
}
