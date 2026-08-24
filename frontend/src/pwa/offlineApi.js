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
  flagCard as netFlagCard,
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
  enqueueFile, enqueueDiscard, enqueueAcknowledge, enqueueFlag, flush,
} from './outbox';
import { isOnline } from './connectivity';

// Drive mode: the installed PWA reads its review set from the Drive-pulled IndexedDB
// (drivePull.js), never the server — the laptop may be off. Grades always queue (the
// Drive mailbox / server replays them later). The desktop full site never sets this, so
// its behaviour is unchanged. MobileLayout flips it on.
let driveMode = false;
export function setDriveMode(on) { driveMode = on; }
export function isDriveMode() { return driveMode; }

// "Couldn't reach a working origin" — the signal to fall through to the IndexedDB cache
// instead of surfacing an error. Two shapes count:
//   • TypeError            → fetch itself threw (DNS down, connection refused, CORS) — truly
//                            offline even though navigator.onLine may still read true.
//   • ApiError 5xx / 530   → the origin/proxy ANSWERED but is broken: Cloudflare tunnel down
//                            (530), gateway errors (502/503/504), or a crashed backend (500).
//                            These are NOT the app's fault and NOT a sign-out — treat exactly
//                            like offline and serve the downloaded set.
// A real 401/403 (needs login) or other 4xx (bad request) is a genuine error → re-throw.
function isServerUnreachable(e) {
  if (e instanceof TypeError) return true;
  if (e instanceof ApiError) {
    return e.status === 530 || e.status === 500 || e.status === 502 ||
           e.status === 503 || e.status === 504;
  }
  return false;
}

// The ONE offline-resilience shape reused by every function below except the deferred-test
// trio (buildAssignmentOffline/submitAttemptOffline/completeAssignmentOffline — those are
// driveMode-gated in-memory session state, not a per-call live-vs-queue decision, so they
// don't fit this): attempt `live()` only when not driveMode (driveMode never hits the
// server directly for these — it defers to the Drive-pulled cache or the mailbox) and
// actually online; on an unreachable server, or when skipped for the reasons above, fall
// back to `fallback()`. A genuine error (a real 401/4xx `live()` chooses to throw) is NOT
// swallowed — it propagates so the caller's own handling (e.g. a login prompt) still runs.
// `live()` may itself decide to queue-and-return for a case it wants to handle specially
// (captureUrl/captureText's 401 → queued with `reason:'auth'` instead of a login rethrow).
async function withOfflineFallback(live, fallback) {
  if (!driveMode && isOnline()) {
    try { return await live(); }
    catch (e) { if (!isServerUnreachable(e)) throw e; }
  }
  return fallback();
}

// Review list — network when online, downloaded IDB subset when offline. Every branch
// yields { notes: [{ path, hasCards }], hasMore } so the store's allocator (reviewPlan.js)
// can split flashcard vs read tracks identically on desktop and phone.
export async function fetchReviewOffline(offset = 0, limit = 40) {
  return withOfflineFallback(
    async () => {
      const res = await netFetchReview(offset, limit);
      return { notes: normalizeNotes(res?.notes), hasMore: Boolean(res?.hasMore) };
    },
    () => localReviewPage(offset, limit),
  );
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
  const fromCache = async () => {
    const rec = await getReviewNote(fullPath);
    if (rec?.content != null) return rec.content;
    throw new ApiError(503); // not downloaded for offline
  };
  return withOfflineFallback(() => netFetchNoteContent(fullPath), fromCache);
}

// Grade — POST when online; otherwise optimistic + queue for replay on reconnect. A real
// 401 is NOT queued here — isServerUnreachable() only treats 5xx/530/TypeError as
// unreachable, so a 401 propagates and the UI prompts login instead.
export async function gradeNoteOffline(notePath, band) {
  return withOfflineFallback(
    () => netGradeNote(notePath, band),
    async () => { await enqueueGrade(notePath, band); return { notePath, band, queued: true, due: null }; },
  );
}

// Capture a shared/pasted link → backend ingest. Queues when offline / on 401.
// trackOpts (tracks/FLOWS.md Phase 1b): { trackId } tags an existing track, or
// { newTrackTitle, newTrackType? } creates one on the fly — resolved server-side
// (CaptureController.resolveTrackId). Omitted ⇒ today's exact untagged behavior.
export async function captureUrl(url, trackOpts = {}) {
  return withOfflineFallback(
    async () => {
      const res = await fetch('/api/capture', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'same-origin',
        body: JSON.stringify({ url, ...trackOpts }),
      });
      if (res.ok) return { queued: false };
      // 401 gets its own queued-with-reason result (not a login rethrow) — capture is a
      // fire-and-forget action the UI doesn't block on, so it just queues for replay
      // after the user signs back in, same as any other unreachable case.
      if (res.status === 401) { await enqueueCapture(url, trackOpts); return { queued: true, reason: 'auth' }; }
      throw new ApiError(res.status);
    },
    async () => { await enqueueCapture(url, trackOpts); return { queued: true }; },
  );
}

// Capture a typed raw note (brain dump) → text ingest → Learn inbox. Mirrors captureUrl;
// driveMode is irrelevant here (capture always needs the server — there's no local queue
// bypass), but withOfflineFallback's driveMode gate is harmless since driveMode is never
// set true for a surface that calls this.
export async function captureText(text, title, trackOpts = {}) {
  return withOfflineFallback(
    async () => {
      const res = await fetch('/api/capture', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'same-origin',
        body: JSON.stringify({ text, title, ...trackOpts }),
      });
      if (res.ok) return { queued: false };
      if (res.status === 401) {
        await enqueueCaptureText(text, title, trackOpts);
        return { queued: true, reason: 'auth' };
      }
      throw new ApiError(res.status);
    },
    async () => { await enqueueCaptureText(text, title, trackOpts); return { queued: true }; },
  );
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
  return withOfflineFallback(netFetchInbox, pulledInbox);
}

export async function fileInboxOffline(path, targetFolder, content) {
  return withOfflineFallback(
    () => netFileInbox(path, targetFolder, content),
    async () => {
      await enqueueFile(path, targetFolder, content);
      await dropFromInbox(i => i.path !== path);
      return { path, queued: true };
    },
  );
}

export async function discardInboxOffline(path) {
  return withOfflineFallback(
    () => netDiscardInbox(path),
    async () => {
      await enqueueDiscard(path);
      await dropFromInbox(i => i.path !== path);
      return { queued: true };
    },
  );
}

export async function acknowledgeOffline(captureId) {
  return withOfflineFallback(
    () => netAcknowledge(captureId),
    async () => {
      await enqueueAcknowledge(captureId);
      await dropFromInbox(i => i.captureId !== captureId);
      return { queued: true };
    },
  );
}

// ── Offline flashcard flagging ─────────────────────────────────────────────────
// Same shape as the inbox actions above. The regen a flag kicks off is best-effort
// either way — see MailboxConsumeService.applyFlag / CardController.flag server-side.
export async function flagCardOffline(cardId, reason) {
  return withOfflineFallback(
    () => netFlagCard(cardId, reason),
    async () => { await enqueueFlag(cardId, reason); return { queued: true }; },
  );
}

// Replay the outbox — call on reconnect (and after re-login).
export { flush as flushOutbox };
