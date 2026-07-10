// Hybrid-review allocator — the single source of truth for splitting a day's due
// notes into a flashcard track and a read-and-self-grade track, under two caps.
// Runs identically online and offline (the store feeds it whichever due list it
// has), so desktop and phone always agree on the split.
//
// Inputs:
//   notes: [{ path, hasCards }]  — DUE notes, already ordered oldest-due-first.
//   flashcardMax        — daily flashcard ceiling (settings.maxDailyFlashcards).
//   totalMax            — daily total ceiling (settings.maxDailyReviews).
//   flashcardsDoneToday — flashcard sessions already completed today; shrinks the
//                         remaining flashcard budget so a mid-day reload can't push
//                         the day past flashcardMax.
//
// Carryover is emergent, not stored: a note only leaves the due queue when it's
// graded, so a skipped note stays due and AGES — tomorrow it sorts earlier and wins
// a flashcard slot ahead of newly-due notes. That is "flashcards-first carryover"
// without any per-day plan table. Only card-bearing notes ever take a flashcard
// slot; everything else (no cards, or once the budget is spent) is read-track.
export function allocateTracks(notes, { flashcardMax = 0, totalMax = 50, flashcardsDoneToday = 0 } = {}) {
  const budget = Math.max(0, flashcardMax - flashcardsDoneToday);
  let used = 0;
  const out = [];
  for (const n of notes) {
    if (out.length >= totalMax) break;                 // hard daily ceiling
    const flashcard = Boolean(n.hasCards) && used < budget;
    out.push({ ...n, track: flashcard ? 'flashcard' : 'read' });
    if (flashcard) used += 1;
  }
  return out;
}
