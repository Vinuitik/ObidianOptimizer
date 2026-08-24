// Offline degraded search — used by useSearch.js when the server is unreachable.
//
// GAP (see FLOWS.md / task handoff): this is KEYWORD substring matching over the
// cached reviewNotes content, NOT semantic/embedding search. True offline semantic
// search would need to embed the typed query text, and there is no way to do that
// offline — the embedder's model (EmbeddingGemma) only runs server-side (embedder/
// container), the backend's /notes/vectors endpoint returns pre-computed DOCUMENT
// vectors only (no query endpoint reachable when offline by definition), and the
// model is prompt-asymmetric (kind: "document" vs "query" — see EmbeddingService in
// the Java backend) so a document-side vector cannot stand in for a query embedding
// even if one were reachable. Making real offline semantic search work would require
// shipping an embedding model into the browser (onnxruntime-web + tokenizer + the
// ~100MB+ ONNX weights) — a separate, large capability, not built here.
//
// So the cached 'noteVectors' store (db.js) is populated and ready, but currently has
// no offline consumer: this fallback deliberately does NOT touch it, to avoid
// misrepresenting keyword matches as semantic ones.
import { getAllReviewNotes } from '../pwa/db';

const SNIPPET_LEN = 150;

function tokenize(q) {
  return (q || '').toLowerCase().split(/\s+/).filter(Boolean);
}

function countOccurrences(haystack, needle) {
  if (!needle) return 0;
  let count = 0;
  let idx = 0;
  while ((idx = haystack.indexOf(needle, idx)) !== -1) {
    count++;
    idx += needle.length;
  }
  return count;
}

function snippetAround(content, token) {
  const lower = content.toLowerCase();
  const at = token ? lower.indexOf(token) : -1;
  if (at === -1) return content.slice(0, SNIPPET_LEN) + (content.length > SNIPPET_LEN ? '...' : '');
  const start = Math.max(0, at - 40);
  const end = Math.min(content.length, start + SNIPPET_LEN);
  return (start > 0 ? '...' : '') + content.slice(start, end) + (end < content.length ? '...' : '');
}

// Returns [{notePath, snippet, score}] — same shape as the online /search response,
// scoped to whatever's currently cached for offline review (reviewNotes store).
export async function offlineKeywordSearch(query, limit = 10) {
  const tokens = tokenize(query);
  if (tokens.length === 0) return [];

  const notes = await getAllReviewNotes();
  const scored = [];

  for (const note of notes) {
    const title = (note.shortName || '').toLowerCase();
    const content = (note.content || '').toLowerCase();
    let score = 0;
    for (const t of tokens) {
      score += countOccurrences(title, t) * 3;   // title hits weighted above body hits
      score += countOccurrences(content, t);
    }
    if (score > 0) {
      scored.push({
        notePath: note.path,
        snippet: snippetAround(note.content || '', tokens[0]),
        score,
      });
    }
  }

  scored.sort((a, b) => b.score - a.score);
  return scored.slice(0, limit);
}
