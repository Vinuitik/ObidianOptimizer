// Inbox grouping helpers for the Learn queue (InboxReview): group key, reading order, tree.
import { parseSourceRegion } from './inboxParse';

// A note's grouping key: its capture (ingest pipeline), else its on-disk _inbox subfolder
// (agent `create_note(folder=)` sessions + the user's own drag-drop mini-folders), else its
// own path (a note loose in _inbox root is its own group). This one key drives color banding,
// contiguous ordering, AND the folder tree, so all three agree on what "one group" means.
// A track item whose note still sits in _inbox is already shown by the Learn queue's units.
export const isInboxNotePath = p => /(^|[/\\])_inbox[/\\]/.test(p || '');

export const inboxGroupKey = it => it.captureId || it.inboxFolder || it.path;

// Reorder so notes from the same source are contiguous (color bands read as groups),
// preserving each source's first-appearance order. WITHIN a source, order by the note's
// position IN the source (min page for a PDF, min start-time for a/v) so review flows in
// reading order instead of the LLM's topical emit order — otherwise pages ricochet
// (24,25 → 4,5 → 41) and there's no sane way to review. Falls back to captureSeq when a
// note has no positional anchor (e.g. plain text). `path` keys un-captured/in-place items.
export function groupBySource(items) {
  const firstSeen = new Map();
  items.forEach((it, i) => {
    const k = inboxGroupKey(it);
    if (!firstSeen.has(k)) firstSeen.set(k, i);
  });
  return items
    .map((it, i) => ({ it, i, pos: sourcePosition(it) }))
    .sort((a, b) => {
      const ga = firstSeen.get(inboxGroupKey(a.it));
      const gb = firstSeen.get(inboxGroupKey(b.it));
      if (ga !== gb) return ga - gb;
      // Positional order within the source (page / timestamp). Positionless notes sort last.
      if (a.pos !== b.pos) return a.pos - b.pos;
      const sa = a.it.captureSeq ?? 0, sb = b.it.captureSeq ?? 0;
      if (sa !== sb) return sa - sb;
      // Same position → sub-order (manual splits): #N before #N-1 before #N-2.
      const ma = a.it.captureSeqMinor ?? 0, mb = b.it.captureSeqMinor ?? 0;
      if (ma !== mb) return ma - mb;
      return a.i - b.i;
    })
    .map(x => x.it);
}

// A note's position within its source, as a sortable number: PDF → its lowest page;
// audio/video → its clip start (seconds). No anchor (plain text) → Infinity (sorts last,
// then captureSeq decides). Reuses the same footer parse the splice viewer reads.
function sourcePosition(item) {
  const r = parseSourceRegion(item?.content || '', item?.source);
  if (r.pages && r.pages.length) return Math.min(...r.pages);
  if (r.startSeconds != null) return r.startSeconds;
  return Infinity;
}

// The order badge for an inbox row: "#N" for an original note, "#N-M" for a note manually
// split off it (capture-seq-minor). captureSeq is 0-based on the wire, shown 1-based.
export function captureLabel(item) {
  if (item?.captureSeq == null) return null;
  const major = item.captureSeq + 1;
  const minor = item.captureSeqMinor ?? 0;
  return minor > 0 ? `#${major}-${minor}` : `#${major}`;
}

// Build the collapsible Learn-queue tree from a flat, already-ordered item list
// (groupBySource output). A standalone note with a captureId becomes a member of a
// FOLDER node keyed on that source; within a source, a note with `chapter` set nests one
// level deeper into a CHAPTER node (PDF only — see backend extract_pdf._toc_chapters).
// in-place notes and any note without a captureId (legacy, pre-folder-staging inbox
// notes) pass through as plain LEAF nodes, exactly like the old flat row list.
// `groupSuggestedFolder`/`chapterSuggestedFolder` (from GET /inbox) ride along on the
// folder/chapter nodes as the "file this whole folder here?" pre-pick.
export function buildInboxTree(items) {
  const nodes = [];
  const folderByKey = new Map();
  const mcpByKey = new Map();
  for (const it of items) {
    // Un-captured note living in an _inbox subfolder (agent session or user mini-folder):
    // group into a MCP folder node keyed on that subfolder. `folderPath` is the subfolder
    // RELATIVE to _inbox — the move/file target. `mcp: true` marks it a drop target for
    // drag-drop reorg (capture/chapter nodes are pipeline-owned and stay non-droppable).
    if (!it.inPlace && !it.captureId && it.inboxFolder) {
      let mf = mcpByKey.get(it.inboxFolder);
      if (!mf) {
        mf = {
          type: 'folder', mcp: true, key: `folder:${it.inboxFolder}`,
          folderPath: it.inboxFolder,
          title: it.inboxFolder.split('/').pop(),
          suggestedFolder: it.groupSuggestedFolder,
          items: [], chapters: [], _chapterByKey: new Map(),
        };
        mcpByKey.set(it.inboxFolder, mf);
        nodes.push(mf);
      }
      mf.items.push(it);
      continue;
    }
    if (it.inPlace || !it.captureId) { nodes.push({ type: 'leaf', item: it }); continue; }
    let folder = folderByKey.get(it.captureId);
    if (!folder) {
      folder = {
        type: 'folder', key: it.captureId,
        title: it.sourceTitle || it.title || 'Source',
        suggestedFolder: it.groupSuggestedFolder,
        items: [], chapters: [], _chapterByKey: new Map(),
      };
      folderByKey.set(it.captureId, folder);
      nodes.push(folder);
    }
    if (it.chapter) {
      let ch = folder._chapterByKey.get(it.chapter);
      if (!ch) {
        ch = {
          type: 'chapter', key: `${it.captureId}::${it.chapter}`, label: it.chapter,
          suggestedFolder: it.chapterSuggestedFolder, items: [],
        };
        folder._chapterByKey.set(it.chapter, ch);
        folder.chapters.push(ch);
      }
      ch.items.push(it);
    } else {
      folder.items.push(it);
    }
  }
  return nodes;
}

// Every note under a folder node (its direct items + every chapter's items) — the set
// "File folder" moves as one unit.
export function folderAllItems(folder) {
  return [...folder.items, ...folder.chapters.flatMap(c => c.items)];
}
