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
