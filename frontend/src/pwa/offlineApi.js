// The offline seam. Drop-in replacements for the three api/notes functions the
// review flow uses, made offline-aware so the SAME leaf components (ReviewPage,
// FlashcardSession, SlideshowReview) work offline with zero component edits.
//
// Wiring (P3 activation): point the store at these instead of api/notes for the
// three functions below — e.g. in src/store/useStore.js swap the imports of
// fetchReview / fetchNoteContent / gradeNote for the *Offline versions here.
// Until then this module is additive and inert.
import {
  fetchReview as netFetchReview,
  fetchNoteContent as netFetchNoteContent,
  gradeNote as netGradeNote,
  ApiError,
} from '../api/notes';
import { getAllReviewNotes, getReviewNote } from './db';
import { enqueueGrade, enqueueCapture, flush } from './outbox';
import { isOnline } from './connectivity';

// Review list — network when online, downloaded IDB subset when offline.
export async function fetchReviewOffline(offset = 0, limit = 40) {
  if (isOnline()) {
    try { return await netFetchReview(offset, limit); }
    catch (e) { if (!(e instanceof TypeError)) throw e; } // network blip → fall through
  }
  const all = await getAllReviewNotes();
  const page = all.slice(offset, offset + limit);
  return { notes: page.map(n => n.path), hasMore: offset + limit < all.length };
}

// Note text — network when online, downloaded copy when offline.
export async function fetchNoteContentOffline(fullPath) {
  if (isOnline()) {
    try { return await netFetchNoteContent(fullPath); }
    catch (e) { if (!(e instanceof TypeError)) throw e; }
  }
  const rec = await getReviewNote(fullPath);
  if (rec?.content != null) return rec.content;
  throw new ApiError(503); // not downloaded for offline
}

// Grade — POST when online; otherwise optimistic + queue for replay on reconnect.
export async function gradeNoteOffline(notePath, band) {
  if (isOnline()) {
    try { return await netGradeNote(notePath, band); }
    catch (e) {
      if (e instanceof ApiError && e.status === 401) throw e; // let UI prompt login
      // network failure → queue
    }
  }
  await enqueueGrade(notePath, band);
  return { notePath, band, queued: true, due: null };
}

// Capture a shared/pasted link → backend ingest. Queues when offline / on 401.
export async function captureUrl(url) {
  if (isOnline()) {
    try {
      const res = await fetch('/api/capture', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'same-origin',
        body: JSON.stringify({ url }),
      });
      if (res.ok) return { queued: false };
      if (res.status === 401) { await enqueueCapture(url); return { queued: true, reason: 'auth' }; }
      throw new ApiError(res.status);
    } catch (e) {
      if (e instanceof ApiError) throw e;
      // network failure → queue
    }
  }
  await enqueueCapture(url);
  return { queued: true };
}

// Replay the outbox — call on reconnect (and after re-login).
export { flush as flushOutbox };
