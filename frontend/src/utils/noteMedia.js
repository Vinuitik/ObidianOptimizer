// Which same-origin media URLs a note needs to be reviewable OFFLINE: its image embeds and,
// when the source is a local A/V file the ingest downloaded into the vault, that file. The
// offline warm step (pwa/warmMedia) fetches these directly from the server while online and
// caches them; retention evicts anything not in the current scope.
//
// URL shapes mirror SourceSplicePanel.mediaUrl / nginx: vault `resources/…` → `/vault-media/…`,
// `_workspace/…` → `/workspace/…`, and image embeds `![[name]]` → `/api/images/name`.
import { parseSourceRegion } from './inboxParse';

const VIDEO_AUDIO_RE = /\.(mp4|mkv|webm|mov|m4v|mp3|m4a|wav|ogg|flac)$/i;

function enc(p) {
  return p.split('/').map(encodeURIComponent).join('/');
}

// The server transcodes A/V to a lightweight rendition once; the warm downloads THAT but
// caches it under the canonical player URL (`key`), so playback code is unchanged and offline
// it transparently gets the small file. Images have no rendition → fetch == key.
function renditionUrl(vaultPath) {
  return `/media-rendition?path=${encodeURIComponent(vaultPath.replace(/^\.?\//, ''))}`;
}

// A vault-relative path → the same-origin URL that serves it (or null if not a vault path).
export function vaultMediaUrl(path) {
  if (!path || /^https?:\/\//.test(path)) return null;   // external ref → streamed live, not warmed
  const p = path.replace(/^\.?\//, '');
  if (p.startsWith('resources/'))  return '/vault-media/' + enc(p.slice('resources/'.length));
  if (p.startsWith('_workspace/')) return '/workspace/' + enc(p.slice('_workspace/'.length));
  return null;
}

// Image embeds `![[name]]` → `/api/images/name`. Same regex renderMarkdown uses for <img src>.
function imageUrls(content) {
  const urls = [];
  const re = /!\[\[(.*?)\]\]/gs;
  let m;
  while ((m = re.exec(content || '')) !== null) urls.push(`/api/images/${encodeURIComponent(m[1])}`);
  return urls;
}

// Media a note needs offline as {key, fetch} entries: `key` is the canonical URL the player
// requests (and the cache is keyed by); `fetch` is what the warm actually downloads — the
// server rendition for A/V, the file itself for images. PDFs are intentionally excluded —
// offline they render through the server `/pdf-page` endpoint, not as a raw blob. Deduped by key.
export function mediaEntriesForNote(content, source) {
  const byKey = new Map();
  for (const u of imageUrls(content)) byKey.set(u, { key: u, fetch: u });
  try {
    const region = parseSourceRegion(content || '', source);
    if (region.local && VIDEO_AUDIO_RE.test(region.local)) {
      const key = vaultMediaUrl(region.local);
      if (key) byKey.set(key, { key, fetch: renditionUrl(region.local) });
    }
  } catch { /* a note with no parseable source footer just contributes its images */ }
  return [...byKey.values()];
}

// Canonical URLs only (cache keys) — used for retention scoping.
export function mediaUrlsForNote(content, source) {
  return mediaEntriesForNote(content, source).map(e => e.key);
}
