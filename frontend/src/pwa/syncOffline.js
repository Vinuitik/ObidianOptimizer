// "Download for offline" — pull the review subset (notes + their text + media)
// into IndexedDB + the Cache Storage media cache, so review works with no network.
//
// Must run ONLINE (over the real-cert tunnel domain so the SW is active). After
// this, offlineApi serves reviews/text from IDB and the SW serves media from cache.
import { fetchReview, fetchNoteContent } from '../api/notes';
import { putReviewNotes, setMeta } from './db';

const MEDIA_CACHE = 'obsopt-media'; // must match public/sw.js

// Pull image embeds (![[name]]) → the URL renderMarkdown() uses for <img src>.
function mediaUrlsFrom(markdown) {
  const urls = [];
  const re = /!\[\[(.*?)\]\]/gs;
  let m;
  while ((m = re.exec(markdown)) !== null) {
    urls.push(`/api/images/${encodeURIComponent(m[1])}`);
  }
  return urls;
}

function shortNameOf(path) {
  return path.split(/[/\\]/).pop().replace(/\.md$/, '');
}

// limit = how many due notes to take offline. includeMedia caches images too
// (P5); video is intentionally left out by default (quota — see PWA arch §12).
export async function syncForOffline({ limit = 40, includeMedia = true, onProgress } = {}) {
  const { notes } = await fetchReview(0, limit);
  const records = [];
  const allMedia = new Set();

  for (let i = 0; i < notes.length; i++) {
    const path = notes[i];
    let content = '';
    try { content = await fetchNoteContent(path); } catch { /* skip unreadable */ }
    const media = mediaUrlsFrom(content);
    media.forEach(u => allMedia.add(u));
    records.push({ path, shortName: shortNameOf(path), content, media });
    onProgress?.({ done: i + 1, total: notes.length });
  }

  await putReviewNotes(records);

  if (includeMedia && allMedia.size && 'caches' in self) {
    const cache = await caches.open(MEDIA_CACHE);
    // addAll is atomic-all-or-nothing; add individually so one 404 doesn't abort.
    await Promise.all([...allMedia].map(u => cache.add(u).catch(() => {})));
  }

  await setMeta('lastSync', Date.now());
  return { notes: records.length, media: allMedia.size };
}
