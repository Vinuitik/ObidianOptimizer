// "Download for offline" — pull the review subset (notes + their text + media)
// into IndexedDB + the Cache Storage media cache, so review works with no network.
//
// Must run ONLINE (over the real-cert tunnel domain so the SW is active). After
// this, offlineApi serves reviews/text from IDB and the SW serves media from cache.
// The media step is delegated to warmReviewMedia (the SAME engine the Drive-linked path
// uses) so this path also caches EVERYTHING — images, A/V renditions, PDF pages, other
// source files — not just images. onStage?.({ stage, done, total }) reports each phase.
import { fetchReview, fetchNoteContent, fetchNames } from '../api/notes';
import { putReviewNotes, setMeta } from './db';
import { warmReviewMedia } from './warmMedia';

function shortNameOf(path) {
  return path.split(/[/\\]/).pop().replace(/\.md$/, '');
}

// limit = how many due notes to take offline. includeMedia warms all referenced media.
export async function syncForOffline({ limit = 40, includeMedia = true, onStage } = {}) {
  const { notes } = await fetchReview(0, limit);
  const records = [];

  for (let i = 0; i < notes.length; i++) {
    // fetchReview returns note OBJECTS ({ path, track, hasCards }) — not bare path
    // strings. Pull the path out; treating the object as a string here was the
    // "e.split is not a function" sync crash on non-Drive (server-direct) clients.
    const path = notes[i].path;
    let content = '';
    try { content = await fetchNoteContent(path); } catch { /* skip unreadable */ }
    records.push({ path, shortName: shortNameOf(path), content });
    onStage?.({ stage: 'notes', done: i + 1, total: notes.length });
  }

  await putReviewNotes(records);

  // Piggyback: cache the full vault's note names (path list) for offline search/link
  // fallback (utils/offlineSearch.js) — simple substring match, no embeddings. Best-effort:
  // a failure here must never fail the review sync; the name cache just stays stale/missing
  // until the next successful sync (drift accepted).
  try {
    await setMeta('cachedNoteNames', await fetchNames());
  } catch { /* offline name cache is best-effort */ }

  let media = { warmed: 0, byPhase: {} };
  if (includeMedia) {
    // Reads the reviewNotes we just wrote; caches images + A/V + PDF pages + other files.
    media = await warmReviewMedia({ onProgress: p => onStage?.({ stage: p.phase, done: p.done, total: p.total }) })
      .catch(() => media);
  }

  await setMeta('lastSync', Date.now());
  return { notes: records.length, media: media.warmed, byPhase: media.byPhase };
}
