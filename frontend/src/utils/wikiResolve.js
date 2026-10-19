// Resolve a wiki-link target ("Folder/Note#Heading|alias") to a vault path via the store's
// noteIndex (lowercased basename-without-.md -> full path). Full lowercased target first, then basename.
export function cleanWikiTarget(raw) {
  return String(raw ?? '').split('|')[0].split('#')[0].trim();
}

export function resolveWikiTarget(noteIndex, raw) {
  const target = cleanWikiTarget(raw).replace(/\.md$/i, '');
  if (!target || !noteIndex) return null;
  const basename = target.split(/[/\\]/).pop().toLowerCase();
  return noteIndex.get(target.toLowerCase()) ?? noteIndex.get(basename) ?? null;
}
