// Persists an in-progress flashcard test so it survives ReviewPage/FlashcardSession unmount
// (PWA tab switch, route change) and an app kill. localStorage, not sessionStorage: the
// installed PWA can be killed by the OS and sessionStorage dies with it.
//
// Two entries, both day-scoped (stale ones are ignored/overwritten):
//   obsOpt_flashSession → { notePath, assignmentId, assignment, idx, answers, flagged,
//                           phase: 'quiz'|'result', verdicts, completion, finished }
//                         at most ONE active test; cleared when the user leaves the test.
//   obsOpt_flashDone    → { date, paths: [...] } notes whose test was completed today.
//                         Drive mode: the local IDB due-list still lists a graded note until
//                         sync, so fetchReviewNotes filters these out of reviewNotes.

const SESSION_KEY = 'obsOpt_flashSession';
const DONE_KEY = 'obsOpt_flashDone';
const today = () => new Date().toISOString().slice(0, 10);

function read(key) {
  try { return JSON.parse(localStorage.getItem(key) || 'null'); } catch { return null; }
}
function write(key, val) {
  try { localStorage.setItem(key, JSON.stringify(val)); } catch { /* quota/private mode: best effort */ }
}

// Active test for `notePath` (or whichever one exists when notePath is omitted).
export function loadFlashSession(notePath) {
  const s = read(SESSION_KEY);
  if (!s || s.date !== today()) return null;
  if (notePath != null && s.notePath !== notePath) return null;
  return s;
}

export function saveFlashSession(state) {
  write(SESSION_KEY, { ...state, date: today() });
}

export function clearFlashSession(notePath) {
  const s = read(SESSION_KEY);
  if (!s || notePath == null || s.notePath === notePath) {
    try { localStorage.removeItem(SESSION_KEY); } catch { /* ignore */ }
  }
}

export function markNoteDone(notePath) {
  const cur = read(DONE_KEY);
  const paths = cur?.date === today() ? cur.paths : [];
  if (!paths.includes(notePath)) write(DONE_KEY, { date: today(), paths: [...paths, notePath] });
}

export function doneNotePaths() {
  const cur = read(DONE_KEY);
  return cur?.date === today() ? new Set(cur.paths) : new Set();
}
