// Offline degraded search — used by useSearch.js when the server is unreachable.
//
// Simple substring match on note NAME (filename), over the full vault's path list cached
// in IndexedDB (meta.cachedNoteNames — see pwa/syncOffline.js and pwa/drivePull.js for the
// piggyback that keeps it filled). Not content search and not semantic — true offline
// semantic search would need embedding the typed query client-side, which isn't achievable
// here (the embedder only runs server-side). Name match is simple, cheap, and covers the
// actual use case (search-by-title, wiki-link autocomplete) without that gap.
import { getMeta } from '../pwa/db';

function folderOf(path) {
  const i = path.lastIndexOf('/');
  return i === -1 ? '' : path.slice(0, i);
}

// Returns [{notePath, snippet}] — same shape as the online /search response.
export async function offlineKeywordSearch(query, limit = 10) {
  const q = (query || '').trim().toLowerCase();
  if (!q) return [];

  const names = (await getMeta('cachedNoteNames')) || [];
  const scored = [];

  for (const path of names) {
    const title = path.split(/[/\\]/).pop().replace(/\.md$/i, '').toLowerCase();
    const at = title.indexOf(q);
    if (at === -1) continue;
    // Prefix matches first, then earlier matches, then shorter titles (tighter match).
    const score = (at === 0 ? 1000 : 0) - at * 10 - title.length;
    scored.push({ notePath: path, snippet: folderOf(path), score });
  }

  scored.sort((a, b) => b.score - a.score);
  return scored.slice(0, limit).map(({ notePath, snippet }) => ({ notePath, snippet }));
}
