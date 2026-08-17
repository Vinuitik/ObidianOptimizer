// Tracks whether TODAY's duty is done, for the "Are you a quitter?" guard (QuitGuard.jsx).
// Duty = (no notes still due for review) AND (at least one Learn/inbox item processed today).
//
// The learn-task half is a localStorage day-stamp bumped from InboxReview when a note is
// filed or acknowledged (genuine processing — discard/delete doesn't count). The review
// half is checked live against the server (fetchReview) so it's page-independent: the guard
// works even if you never opened the Review tab this session.
import { fetchReview } from '../api/notes.js';

const LEARN_KEY = 'obsOpt_learnDoneDate';
const NAG_KEY = 'obsOpt_lastNagAt';
const NAG_COOLDOWN_MS = 20 * 60 * 1000; // shared by web (mouseleave) + mobile (backgrounding) —
// both triggers can fire many times an hour, so without this the nag would spam. Desktop
// close/minimize skip this (rare, deliberate actions) and call the quote dialog directly.

const today = () => new Date().toISOString().slice(0, 10);

// Call after a productive Learn action (file / acknowledge). Idempotent per day.
export function markLearnTaskDone() {
  try { localStorage.setItem(LEARN_KEY, today()); } catch { /* private mode */ }
}

export function learnTaskDoneToday() {
  try { return localStorage.getItem(LEARN_KEY) === today(); } catch { return false; }
}

// Is anything still due for review today? Cheap probe (fetch a single due note).
// On any error (offline, 401, server down) we return false so the guard never nags
// on failures it can't verify.
async function reviewsPending() {
  try {
    const { notes } = await fetchReview(0, 1);
    return Array.isArray(notes) && notes.length > 0;
  } catch { return false; }
}

// True when the user still owes work today. Callers gate on isAuthenticated first.
export async function dutyUnfinished() {
  if (!learnTaskDoneToday()) return true;
  return reviewsPending();
}

// Exit-intent / backgrounding cooldown — call canNag() right before showing a nag, and
// markNagged() right after. Prevents mouseleave/visibilitychange from re-firing every
// time the user's cursor drifts near the tab bar or they briefly switch apps.
export function canNag() {
  try {
    const last = Number(localStorage.getItem(NAG_KEY) || 0);
    return Date.now() - last > NAG_COOLDOWN_MS;
  } catch { return true; }
}

export function markNagged() {
  try { localStorage.setItem(NAG_KEY, String(Date.now())); } catch { /* private mode */ }
}
