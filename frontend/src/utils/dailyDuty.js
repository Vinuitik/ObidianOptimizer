// Tracks whether TODAY's duty is done, for the "Are you a quitter?" guard (QuitGuard.jsx).
// Duty = (no notes still due for review) AND (at least one Learn/inbox item processed today).
//
// The learn-task half is a localStorage day-stamp bumped from InboxReview when a note is
// filed or acknowledged (genuine processing — discard/delete doesn't count). The review
// half is checked live against the server (fetchReview) so it's page-independent: the guard
// works even if you never opened the Review tab this session.
import { fetchReview } from '../api/notes.js';

const LEARN_KEY = 'obsOpt_learnDoneDate';

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
