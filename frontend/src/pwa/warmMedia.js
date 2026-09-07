// Warm the offline media cache, preferring DIRECT-FROM-SERVER (transcoded A/V, rendered PDF
// pages) but falling back to Drive for raw vault files (images, plain non-transcoded files —
// see isRawVaultResource) when the server fetch fails or was never reachable at all. Scope =
// the media referenced by the pulled review notes + the Learn inbox. Retention: evict cached
// media that falls out of that scope, so the phone store stays lean (user's "keep the phone
// clean").
//
// Best-effort: never throws — a failed warm just leaves the last-good cache in place. Call
// from drivePull.refreshAndPull after the note set lands in IndexedDB — runs regardless of
// whether the server answered, since the Drive fallback doesn't need it to.
import { getAllReviewNotes, getMeta } from './db';
import { mediaEntriesForNote } from '../utils/noteMedia';
import { getCreds } from './setup';
import { getAccessToken, driveFindByVaultPath, driveDownload } from './drive';
import { deriveKey, decrypt } from './crypto';

const MEDIA_CACHE = 'obsopt-media';   // must match public/sw.js + syncOffline.js
// Only prune URLs the warm itself manages — never touch app icons or anything else the SW
// may have parked in this cache. Tested against the PATHNAME (query stripped).
const MANAGED_RE = /^\/(vault-media|workspace|api\/images|pdf-page)(\/|$)/;

// Cache keys can carry a query string (/pdf-page?path=…&page=N), so identity is pathname+search,
// NOT pathname alone — otherwise every PDF page collapses to "/pdf-page" and retention nukes them.
const idOf = (urlStr) => { const u = new URL(urlStr); return u.pathname + u.search; };

// Which progress bucket a key belongs to (drives the "images 3/12 → PDF 1/4" UI label).
// NB image embeds actually resolve to /vault-media/... (obsidianImagePlugin.mediaUrl), not
// /api/images/... (that prefix is stale here — dead code, never matches) — so today image
// embeds fall into the 'media' bucket alongside A/V. Left as-is rather than reclassifying
// mid-fix; isRawVaultResource below is what actually distinguishes them for the Drive
// fallback, independent of this label.
function phaseOf(key) {
  if (key.startsWith('/pdf-page')) return 'pdf';
  if (key.startsWith('/api/images/')) return 'images';
  return 'media';   // A/V renditions + any other source file
}

// A raw vault file (image embed, or "other local file") the server would have served
// byte-for-byte as-is — fetch === key, i.e. NOT a transcoded A/V rendition (/media-rendition)
// or a server-rendered PDF page (/pdf-page, where fetch also happens to equal key but the
// content is generated, not a file that exists anywhere). Only these map 1:1 onto a file the
// vault sync already mirrors to Drive (SyncService.java), so only these get a Drive fallback.
function isRawVaultResource(entry) {
  return entry.fetch === entry.key && entry.key.startsWith('/vault-media/');
}

// /vault-media/<subdir>/<name> (URL-encoded) → the vault-relative path the SAME file was
// uploaded under (DriveService.uploadFile's appProperties.vault_path) — see vaultMediaUrl's
// forward mapping in utils/noteMedia.js, inverted.
function vaultRelPathFromKey(key) {
  const m = key.match(/^\/vault-media\/(.+)$/);
  if (!m) return null;
  try { return 'resources/' + m[1].split('/').map(decodeURIComponent).join('/'); }
  catch { return null; }
}

const EXT_MIME = {
  png: 'image/png', jpg: 'image/jpeg', jpeg: 'image/jpeg', gif: 'image/gif',
  webp: 'image/webp', svg: 'image/svg+xml', bmp: 'image/bmp', avif: 'image/avif',
};
function mimeFor(path) {
  const ext = path.split('.').pop()?.toLowerCase();
  return EXT_MIME[ext] || 'application/octet-stream';
}

// Reused across every file in one warm() call — the token refresh + PBKDF2 key derivation
// (deliberately slow, 310k iterations) only need to happen once, not per file.
let driveCtx = null;
async function driveMediaCtx() {
  if (driveCtx) return driveCtx;
  const creds = await getCreds().catch(() => null);
  if (!creds?.driveFolderId) return null;
  try {
    const token = await getAccessToken(creds);
    const key = await deriveKey(creds.passphrase);
    driveCtx = { token, key };
    return driveCtx;
  } catch { return null; }
}

// Direct-from-Drive fallback for when the server-direct fetch above failed or was never
// reachable at all — the same mirrored resources/ file the vault sync already uploads
// (SyncService.java), read the same way proofReadNote() proves for notes. Video/audio and
// PDF pages aren't attempted here (see isRawVaultResource) — those need the server's
// transcoding/rendering, which Drive has no equivalent of.
async function warmFromDrive(entry, cache) {
  const relPath = vaultRelPathFromKey(entry.key);
  if (!relPath) return false;
  const ctx = await driveMediaCtx();
  if (!ctx) return false;
  try {
    const file = await driveFindByVaultPath(ctx.token, relPath);
    if (!file) return false;
    const bytes = await decrypt(ctx.key, await driveDownload(ctx.token, file.id));
    await cache.put(entry.key, new Response(bytes, { headers: { 'Content-Type': mimeFor(relPath) } }));
    return true;
  } catch { return false; }
}

// Download EVERYTHING each in-scope note sources (images + A/V renditions + PDF pages + other
// files), keyed under the canonical URL the player requests. Best-effort: never throws.
export async function warmReviewMedia({ onProgress } = {}) {
  if (!('caches' in self)) return { warmed: 0, evicted: 0, wanted: 0, byPhase: {} };
  driveCtx = null; // re-derive fresh creds/token/key each warm() call, don't reuse a stale one

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
      let ok = false;
      try {
        const res = await fetch(list[i].fetch);
        if (res.ok) { await cache.put(list[i].key, res); ok = true; }
      } catch { /* unreachable/oversized/failed transcode — try the Drive fallback below */ }
      // Direct-from-server failed (or the server was never reachable at all — e.g. offline
      // reconnect, mailbox drained but nothing server-side to fetch from): images/plain
      // files can still come straight from Drive, same as notes already do.
      if (!ok && isRawVaultResource(list[i])) ok = await warmFromDrive(list[i], cache);
      if (ok) { warmed++; byPhase[phase]++; }
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
