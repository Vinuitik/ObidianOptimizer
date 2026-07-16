// Which same-origin media URLs a note needs to be reviewable OFFLINE: its image embeds and,
// when the source is a local A/V file the ingest downloaded into the vault, that file. The
// offline warm step (pwa/warmMedia) fetches these directly from the server while online and
// caches them; retention evicts anything not in the current scope.
//
// URL shapes mirror SourceSplicePanel.mediaUrl / nginx: vault `resources/…` → `/vault-media/…`,
// `_workspace/…` → `/workspace/…`, and image embeds `![[name]]` → `/api/images/name`.
import { parseSourceRegion } from './inboxParse';
import { mediaUrl as embedUrl } from './obsidianImagePlugin';

const VIDEO_AUDIO_RE = /\.(mp4|mkv|webm|mov|m4v|mp3|m4a|wav|ogg|flac)$/i;
const PDF_RE = /\.pdf$/i;

function enc(p) {
  return p.split('/').map(encodeURIComponent).join('/');
}

// The server transcodes A/V to a lightweight rendition once; the warm downloads THAT but
// caches it under the canonical player URL (`key`), so playback code is unchanged and offline
// it transparently gets the small file. Images have no rendition → fetch == key.
function renditionUrl(vaultPath) {
  return `/media-rendition?path=${encodeURIComponent(vaultPath.replace(/^\.?\//, ''))}`;
}

// The URL the review Source panel requests for one rasterized PDF page (embedder /pdf-page).
// EXPORTED and reused by SourceSplicePanel so the warm caches byte-identical keys — any drift
// in encoding/param order would make the cached page miss offline. `box` = optional sub-page
// highlight (v2 bbox), rounded exactly as the panel rounds it.
export function pdfPageUrl(vaultPath, page, box, crop = false) {
  let u = `/pdf-page?path=${encodeURIComponent(vaultPath)}&page=${page}`;
  if (box && box.length === 4) u += `&box=${box.map(n => Math.round(n * 10) / 10).join(',')}`;
  // crop=1 (needs a box) renders ONLY the region — the default review view for a mid-page unit.
  if (crop && box && box.length === 4) u += `&crop=1`;
  return u;
}

// A local vault PDF the /pdf-page endpoint can resolve (vault-relative, .pdf), or null for an
// external http PDF. Mirrors SourceSplicePanel.vaultPdfPath.
function vaultPdfPath(ref) {
  const r = (ref || '').trim();
  if (!r || /^https?:\/\//i.test(r)) return null;
  return PDF_RE.test(r) ? r.replace(/^\/+/, '') : null;
}

// A vault-relative path → the same-origin URL that serves it (or null if not a vault path).
export function vaultMediaUrl(path) {
  if (!path || /^https?:\/\//.test(path)) return null;   // external ref → streamed live, not warmed
  const p = path.replace(/^\.?\//, '');
  if (p.startsWith('resources/'))  return '/vault-media/' + enc(p.slice('resources/'.length));
  if (p.startsWith('_workspace/')) return '/workspace/' + enc(p.slice('_workspace/'.length));
  return null;
}

// Embeds `![[name]]` → the SAME URL the Milkdown renderer (obsidianImagePlugin) requests:
// `/vault-media/<subdir>/name`. This MUST match the renderer exactly — caching under the old
// `/api/images/name` (a different endpoint the review view never hits) is what left images
// blank offline despite a "successful" sync. `embedUrl` handles image/video/audio/pdf subdirs.
function imageUrls(content) {
  const urls = [];
  const re = /!\[\[(.*?)\]\]/gs;
  let m;
  while ((m = re.exec(content || '')) !== null) urls.push(embedUrl(m[1]));
  return urls;
}

// Media a note needs offline as {key, fetch} entries: `key` is the canonical URL the player
// requests (and the cache is keyed by); `fetch` is what the warm actually downloads. We cache
// EVERYTHING a note sources, so review works fully offline:
//   • image embeds ![[x]]  → /api/images/x            (fetch == key)
//   • A/V source           → /media-rendition (≤480p) cached under the /vault-media player URL
//   • PDF source           → the referenced page(s) as /pdf-page PNGs (+ bbox-highlight variant)
//   • any other local file → the file itself under /vault-media|/workspace
// External http sources stream live (cross-origin, not cacheable here) and are skipped.
// Deduped by key.
export function mediaEntriesForNote(content, source) {
  const byKey = new Map();
  const add = (key, fetch) => { if (key) byKey.set(key, { key, fetch: fetch ?? key }); };

  for (const u of imageUrls(content)) add(u, u);

  try {
    const region = parseSourceRegion(content || '', source);
    const local = region.local;
    if (local && !/^https?:\/\//i.test(local)) {
      if (VIDEO_AUDIO_RE.test(local)) {
        add(vaultMediaUrl(local), renditionUrl(local));
      } else if (PDF_RE.test(local)) {
        const vp = vaultPdfPath(local);
        if (vp) {
          const pages = region.pages && region.pages.length ? region.pages : [1];
          for (const p of pages) {
            add(pdfPageUrl(vp, p, null));                       // clean full page (no-box notes / full-page toggle)
            const box = region.bboxes?.[p];
            if (box && box.length === 4) {
              add(pdfPageUrl(vp, p, box, true));               // cropped region — the DEFAULT review view
              add(pdfPageUrl(vp, p, box));                     // full page + highlight — the toggle-off state
            }
          }
        }
      } else {
        add(vaultMediaUrl(local));                              // doc / other source file, as-is
      }
    }
  } catch { /* a note with no parseable source footer just contributes its images */ }
  return [...byKey.values()];
}

// Canonical URLs only (cache keys) — used for retention scoping.
export function mediaUrlsForNote(content, source) {
  return mediaEntriesForNote(content, source).map(e => e.key);
}
