// Offline folder-tree derivation — used by InboxReview.jsx's destination picker when
// GET /children is unreachable. No separate tree cache: derives directly from the flat
// note-path list pwa/syncOffline.js / pwa/drivePull.js already cache offline (meta
// 'cachedNoteNames', from GET /names) — same absolute-path format /children uses, so a
// folder picked offline is identical in shape to one picked online.
//
// A folder with zero notes in its subtree is invisible offline (it's not in the note-path
// list) — accepted: you're filing INTO folders that already have content in the normal case.

function dirName(p) {
  return p.replace(/[/\\]+$/, '').replace(/[/\\][^/\\]*$/, '');
}

// Same shape FolderPicker's `loadPath` contract expects: { current, parent, dirs }.
export function offlineChildrenOf(paths, folderPath, vaultRoot) {
  const current = folderPath || vaultRoot;
  if (!current) return { current: '', parent: null, dirs: [] };

  const prefix = current.replace(/[/\\]+$/, '') + '/';
  const children = new Map(); // childPath -> name

  for (const p of paths || []) {
    if (!p.startsWith(prefix)) continue;
    const rest = p.slice(prefix.length);
    const seg = rest.split(/[/\\]/)[0];
    if (!seg || rest === seg) continue; // note sits directly in `current` — not a subfolder
    const childPath = prefix + seg;
    if (!children.has(childPath)) children.set(childPath, seg);
  }

  const dirs = [...children.entries()]
    .filter(([, name]) => name !== '_inbox')
    .map(([path, name]) => ({ path, name }))
    .sort((a, b) => a.name.localeCompare(b.name));

  return { current, parent: current === vaultRoot ? null : dirName(current), dirs };
}
