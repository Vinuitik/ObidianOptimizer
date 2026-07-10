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

// All same-origin media a note needs offline: image embeds + its local A/V source file.
// PDFs are intentionally excluded — offline they render through the server `/pdf-page`
// endpoint (a separate warm concern), not as a raw blob. Deduped, relative URLs.
export function mediaUrlsForNote(content, source) {
  const out = new Set(imageUrls(content));
  try {
    const region = parseSourceRegion(content || '', source);
    if (region.local && VIDEO_AUDIO_RE.test(region.local)) {
      const u = vaultMediaUrl(region.local);
      if (u) out.add(u);
    }
  } catch { /* a note with no parseable source footer just contributes its images */ }
  return [...out];
}
