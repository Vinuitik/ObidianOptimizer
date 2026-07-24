// The offline seam. Drop-in replacements for the three api/notes functions the
// review flow uses, made offline-aware so the SAME leaf components (ReviewPage,
// FlashcardSession, InlineNoteReview) work offline with zero component edits.
//
// Wiring (P3 activation): point the store at these instead of api/notes for the
// three functions below — e.g. in src/store/useStore.js swap the imports of
// fetchReview / fetchNoteContent / gradeNote for the *Offline versions here.
// Until then this module is additive and inert.
import {
  fetchReview as netFetchReview,
  fetchNoteContent as netFetchNoteContent,
  gradeNote as netGradeNote,
  buildAssignment as netBuildAssignment,
  submitAttempt as netSubmitAttempt,
  completeAssignment as netCompleteAssignment,
  ApiError,
} from '../api/notes';
import {
  fetchInbox as netFetchInbox,
  fileInboxNote as netFileInbox,
  discardInboxNote as netDiscardInbox,
  acknowledgeCapture as netAcknowledge,
} from '../api/inbox';
import { getAllReviewNotes, getReviewNote, getAssignmentByNote, getAllAssignments, getMeta, setMeta } from './db';
import {
  enqueueGrade, enqueueCapture, enqueueCaptureText, enqueueAssignment,
  enqueueFile, enqueueDiscard, enqueueAcknowledge, flush,
} from './outbox';
import { isOnline } from './connectivity';

// Drive mode: the installed PWA reads its review set from the Drive-pulled IndexedDB
// (drivePull.js), never the server — the laptop may be off. Grades always queue (the
// Drive mailbox / server replays them later). The desktop full site never sets this, so
// its behaviour is unchanged. MobileLayout flips it on.
let driveMode = false;
export function setDriveMode(on) { driveMode = on; }
export function isDriveMode() { return driveMode; }

// Review list — network when online, downloaded IDB subset when offline. Every branch
// yields { notes: [{ path, hasCards }], hasMore } so the store's allocator (reviewPlan.js)
// can split flashcard vs read tracks identically on desktop and phone.
export async function fetchReviewOffline(offset = 0, limit = 40) {
  if (driveMode) return localReviewPage(offset, limit);      // phone: never hits the server
  if (isOnline()) {
    try {
      const res = await netFetchReview(offset, limit);
      return { notes: normalizeNotes(res?.notes), hasMore: Boolean(res?.hasMore) };
    }
    catch (e) { if (!(e instanceof TypeError)) throw e; }     // network blip → fall through
  }
  return localReviewPage(offset, limit);
}

// Accept both the new shape ([{path,hasCards}]) and the legacy one (string[]), so a
// frontend that's ahead of a not-yet-redeployed backend degrades to read-track instead
// of crashing. Drops any entry without a path.
function normalizeNotes(list) {
  return (list ?? [])
    .map(n => (typeof n === 'string' ? { path: n, hasCards: false }
                                     : { path: n?.path, hasCards: Boolean(n?.hasCards) }))
    .filter(n => typeof n.path === 'string' && n.path.length > 0);
}

// Build a review page from the downloaded IDB set. hasCards = a prebuilt assignment
// exists for the note; ordered oldest-due-first to match the server, so the flashcard
// budget lands on the same (oldest) notes it would online.
async function localReviewPage(offset, limit) {
  const all = await getAllReviewNotes();
  const cardPaths = new Set((await getAllAssignments()).map(a => a.notePath));
  all.sort((a, b) => String(a.srDue ?? '').localeCompare(String(b.srDue ?? '')) ||
                     String(a.path).localeCompare(String(b.path)));
  const notes = all.map(n => ({ path: n.path, hasCards: cardPaths.has(n.path) }));
  const page = notes.slice(offset, offset + limit);
  return { notes: page, hasMore: offset + limit < notes.length };
}

// Note text — network when online, downloaded copy when offline.
export async function fetchNoteContentOffline(fullPath) {
  if (driveMode) {
    const rec = await getReviewNote(fullPath);
    if (rec?.content != null) return rec.content;
    throw new ApiError(503); // not in the pulled set
  }
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
  if (driveMode) {
    // Never hit the server directly in Drive mode — queue for the mailbox / later replay.
    await enqueueGrade(notePath, band);
    return { notePath, band, queued: true, due: null };
  }
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

// Capture a typed raw note (brain dump) → text ingest → Learn inbox. Queues when offline /
// on 401, replays server-direct on reconnect. Mirrors captureUrl; driveMode is irrelevant
// (capture always goes server-direct — the ingest pipeline needs the server regardless).
export async function captureText(text, title) {
  if (isOnline()) {
    try {
      const res = await fetch('/api/capture', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'same-origin',
        body: JSON.stringify({ text, title }),
      });
      if (res.ok) return { queued: false };
      if (res.status === 401) { await enqueueCaptureText(text, title); return { queued: true, reason: 'auth' }; }
      throw new ApiError(res.status);
    } catch (e) {
      if (e instanceof ApiError) throw e;
      // network failure → queue
    }
  }
  await enqueueCaptureText(text, title);
  return { queued: true };
}

// ── Offline flashcard tests (deferred grading) ────────────────────────────────
// In driveMode the phone runs a PRE-BUILT assignment from IndexedDB (server export),
// records the raw answers, and the server grades them on mailbox consume. Online (or
// desktop) these pass straight through to the real endpoints.
let offlineAnswers = {};        // { [cardId]: answer } for the in-progress test
let offlineCtx = null;          // { assignmentId, notePath }

export async function buildAssignmentOffline(scope, points) {
  if (!driveMode) return netBuildAssignment(scope, points);
  const a = await getAssignmentByNote(scope);
  if (!a) throw new Error('No offline test downloaded for this note.');
  offlineAnswers = {};
  offlineCtx = { assignmentId: a.assignmentId, notePath: scope };
  return { id: a.assignmentId, scope, targetPoints: points, cards: a.cards, variants: a.variants };
}

export async function submitAttemptOffline(assignmentId, cardId, answer) {
  if (!driveMode) return netSubmitAttempt(assignmentId, cardId, answer);
  // Record the raw answer; the server is authoritative and grades on consume. No local
  // verdict (open/exercise need the model) — the UI shows "recorded", score arrives on sync.
  offlineAnswers[cardId] = answer ?? '';
  return { verdict: 'RECORDED', pointsEarned: 0, maxPoints: 0, deferred: true };
}

export async function completeAssignmentOffline(assignmentId) {
  if (!driveMode) return netCompleteAssignment(assignmentId);
  const ctx = offlineCtx || { assignmentId, notePath: null };
  await enqueueAssignment(ctx.assignmentId, ctx.notePath, offlineAnswers);
  offlineAnswers = {};
  offlineCtx = null;
  return { notes: [], deferred: true, queued: true };
}

// ── Offline Learn inbox (triage from the Drive-pulled bundle) ─────────────────
// driveMode: read the inbox from the pulled set (meta 'inboxItems'); file/discard/
// acknowledge queue as mailbox events and optimistically drop the item locally so the
// list reflects the action offline. Online/desktop → straight to the server.
async function pulledInbox() { return (await getMeta('inboxItems')) || []; }
async function dropFromInbox(pred) {
  const items = await pulledInbox();
  await setMeta('inboxItems', items.filter(pred));
}

export async function fetchInboxOffline() {
  if (driveMode) return pulledInbox();
  if (isOnline()) {
    try { return await netFetchInbox(); }
    catch (e) { if (!(e instanceof TypeError)) throw e; }  // network blip → fall through
  }
  return pulledInbox();   // offline and not driveMode: still show whatever was last pulled
}

export async function fileInboxOffline(path, targetFolder, content) {
  if (!driveMode && isOnline()) {
    try { return await netFileInbox(path, targetFolder, content); }
    catch (e) { if (!(e instanceof TypeError)) throw e; }
  }
  await enqueueFile(path, targetFolder, content);
  await dropFromInbox(i => i.path !== path);
  return { path, queued: true };
}

export async function discardInboxOffline(path) {
  if (!driveMode && isOnline()) {
    try { return await netDiscardInbox(path); }
    catch (e) { if (!(e instanceof TypeError)) throw e; }
  }
  await enqueueDiscard(path);
  await dropFromInbox(i => i.path !== path);
  return { queued: true };
}

export async function acknowledgeOffline(captureId) {
  if (!driveMode && isOnline()) {
    try { return await netAcknowledge(captureId); }
    catch (e) { if (!(e instanceof TypeError)) throw e; }
  }
  await enqueueAcknowledge(captureId);
  await dropFromInbox(i => i.captureId !== captureId);
  return { queued: true };
}

// Replay the outbox — call on reconnect (and after re-login).
export { flush as flushOutbox };
