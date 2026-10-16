// DFS pre-order traversal of the review queue, mirroring how the notes sit in the
// vault tree: siblings in the same folder before descending into a subfolder, files
// before subdirectories at each level, alphabetical within each group. Used by
// ReviewPage to pick the next note to open instead of a random one.
//
// `fullPath` on review notes is vault-relative with '/' separators (see
// FileRepository.relativize, backend/.../notes/FileRepository.java), e.g.
// "Folder/Sub/note.md" — no leading slash, no drive root.

// Compares two vault-relative paths in canonical DFS pre-order: walks shared
// directory segments, and at the first point of divergence a file (path ends here)
// always sorts before a directory continuation (path keeps going), regardless of
// name; otherwise compares that segment alphabetically.
export function comparePaths(a, b) {
  const segA = a.split('/');
  const segB = b.split('/');
  const n = Math.min(segA.length, segB.length);
  for (let i = 0; i < n; i++) {
    const lastA = i === segA.length - 1;
    const lastB = i === segB.length - 1;
    if (lastA && lastB) {
      if (segA[i] === segB[i]) return 0;
      return segA[i] < segB[i] ? -1 : 1;
    }
    if (lastA !== lastB) {
      // One path ends here (a file at this level), the other continues into a
      // subdirectory — files sort before subdirectories regardless of name.
      return lastA ? -1 : 1;
    }
    if (segA[i] !== segB[i]) {
      return segA[i] < segB[i] ? -1 : 1;
    }
    // Same directory segment — descend.
  }
  return segA.length - segB.length;
}

// Picks the next note after `excludePath` in canonical DFS pre-order, restricted to
// notes still present in `reviewNotes` (the caller's list, already filtered — the
// just-graded note is dismissed from the store before this runs). Wraps to the
// first remaining note (in canonical order) if nothing comes after. Returns null
// when `reviewNotes` is empty.
export function pickNextInOrder(reviewNotes, excludePath) {
  if (!reviewNotes.length) return null;
  const sorted = [...reviewNotes].sort((a, b) => comparePaths(a.fullPath, b.fullPath));
  if (!excludePath) return sorted[0];
  const after = sorted.find(n => comparePaths(n.fullPath, excludePath) > 0);
  return after ?? sorted[0];
}

// ---- Interleaving flashcard-track and read-track notes ----------------------------
// Goal: spread the (few) flashcard notes evenly among the (many) read notes, with the
// ratio derived from what is LEFT (C cards, R reads) every time — no stored plan.
// 2 cards / 8 reads -> C RRRR C RRRR; 3/3 -> C R C R C R.
//
// `hist` = { lastTrack, run }: track of the note just opened and, for reads, how many
// reads in a row since the last card. Right after a card -> read. During a read run
// the run's target length is round((R + run) / (C + 1)) (re-derived from the remaining
// reads plus those already done); a card is due once the run reaches it.
export function nextTrack(C, R, hist = {}) {
  if (C <= 0) return 'read';
  if (R <= 0) return 'flashcard';
  const { lastTrack = null, run = 0 } = hist;
  if (lastTrack == null) return 'flashcard';
  if (lastTrack === 'flashcard') return 'read';
  const gap = Math.round((R + run) / (C + 1));
  return run >= gap ? 'flashcard' : 'read';
}

// History after opening a note of `track`.
export function advanceHistory(hist, track) {
  if (track === 'flashcard') return { lastTrack: 'flashcard', run: 0 };
  return { lastTrack: 'read', run: hist?.lastTrack === 'read' ? hist.run + 1 : 1 };
}

// Picks the next note: chooses the track via nextTrack, then the next note of that
// track in DFS order after `excludePath` (wrapping). Notes without `track` count as read.
export function pickNextInterleaved(reviewNotes, excludePath, hist = {}) {
  if (!reviewNotes.length) return null;
  const cards = reviewNotes.filter(n => n.track === 'flashcard');
  const reads = reviewNotes.filter(n => n.track !== 'flashcard');
  const t = nextTrack(cards.length, reads.length, hist);
  return pickNextInOrder(t === 'flashcard' ? cards : reads, excludePath);
}
