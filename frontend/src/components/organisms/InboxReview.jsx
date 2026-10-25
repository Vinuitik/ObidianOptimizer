import { useState, useEffect, useCallback, useMemo, useRef } from 'react';
import {
  fetchInboxOffline as fetchInbox,
  fileInboxOffline as fileInboxNote,
  discardInboxOffline as discardInboxNote,
  acknowledgeOffline as acknowledgeCapture,
} from '../../pwa/offlineApi';
import { fetchChildren, updateNote } from '../../api/notes';
import { splitInboxNote, moveInboxNote, createInboxFolder } from '../../api/inbox';
import { getMeta, setMeta } from '../../pwa/db';
import { offlineChildrenOf } from '../../utils/offlineFolders';
import { groupBySource, captureLabel, buildInboxTree, folderAllItems, inboxGroupKey } from '../../utils/sourceColor';
import { markLearnTaskDone } from '../../utils/dailyDuty';
import useStore from '../../store/useStore';
import { useIsMobile } from '../../utils/useMediaQuery';
import { itemSourceKind } from '../../utils/sourceKind';
import LearnLayout from '../templates/LearnLayout';
import RsvpReader from '../molecules/RsvpReader';
import MarkdownView from '../molecules/MarkdownView';
import SourceSplicePanel from './SourceSplicePanel';
import FolderPicker from './FolderPicker';
import Button from '../atoms/Button';
import Checkbox from '../atoms/Checkbox';
import Icon from '../atoms/Icon';
import Skeleton from '../atoms/Skeleton';
import SegmentedTabs from '../molecules/SegmentedTabs';
import EmptyState from '../molecules/EmptyState';
import ErrorState from '../molecules/ErrorState';
import styles from './InboxReview.module.css';

const baseName = p => (p || '').replace(/[/\\]+$/, '').split(/[/\\]/).pop() || p;
const dirName  = p => p.replace(/[/\\]+$/, '').replace(/[/\\][^/\\]*$/, '');

// After acting on a note, the next one to review WITHIN its source group: the first remaining
// group member in review order (groupBySource). The acted note is already gone from `list`, so
// what's left flows in reading order. null → the group is exhausted (caller then auto-picks
// into the next group). Uses the same inboxGroupKey as
// color banding/the folder tree (sourceColor.js) — a local captureId-or-path-only copy of this
// used to live here and silently broke auto-advance for inboxFolder-grouped (non-captured)
// notes, since a note with neither captureId nor a shared folder falls through to its own path.
// The preview shows the body only: a leading "# Title" that just repeats the note's own title
// (already in the queue row and the panel header) is dropped. Display-only; the draft is untouched.
const normTitle = t => (t || '').toLowerCase().replace(/[^\p{L}\p{N}]+/gu, ' ').trim();
export function stripLeadingTitle(content, title) {
  const m = /^(\s*---\r?\n[\s\S]*?\r?\n---\r?\n)?(\s*)#\s+([^\r\n]+)\r?\n?/.exec(content || '');
  if (!m || normTitle(m[3]) !== normTitle(title)) return content;
  return (m[1] || '') + content.slice(m[0].length).replace(/^\s*\n/, '');
}

// Learn unit key of a note (matches the backend: <captureId>[/<chapter folder slug>]).
// The chapter slug is the 2nd segment of inboxFolder (`<captureId>/<slug>`).
function unitChapterOf(it) {
  const seg = (it.inboxFolder || '').split('/');
  return it.captureId && seg[0] === it.captureId && seg[1] ? seg[1] : null;
}
export function unitKeyOf(it) {
  if (!it.captureId || it.inPlace) return null;
  const ch = unitChapterOf(it);
  return ch ? `${it.captureId}/${ch}` : it.captureId;
}

const COMPARE_KEY = 'learn:compareSource';
function readCompare() { try { return localStorage.getItem(COMPARE_KEY) === '1'; } catch { return false; } }
function writeCompare(v) { try { localStorage.setItem(COMPARE_KEY, v ? '1' : '0'); } catch { /* storage unavailable */ } }

function nextInGroup(list, groupKey) {
  const inGroup = groupBySource(list).filter(i => inboxGroupKey(i) === groupKey);
  return inGroup[0] || null;
}

// Ingest review (INGESTION_V2_FLOWS §7), note-first (UI_DECLUTTER W1/W5):
//   [queue drawer: Today units / Backlog] · NOTE (one column) · [action bar]
// Non-text sources (video/youtube/pdf/web) add a "Compare with source" toggle that brings back
// the LearnLayout split (desktop) / Source|Note tabs (mobile). Text sources never show a source
// panel (the note IS the source); RSVP is a "Speed read" action on the note instead.
// The destination folder is PROPOSED (find_home) and re-picked from FolderPicker. Filing moves
// the note into its folder + FSRS queue.
export default function InboxReview({ onCount }) {
  const isAuthenticated = useStore(s => s.isAuthenticated);
  const todayUnits      = useStore(s => s.todayUnits);       // null = plan not loaded / tracks off
  const tracks          = useStore(s => s.tracks);
  const fetchTodayPlan  = useStore(s => s.fetchTodayPlan);
  const fetchTracks     = useStore(s => s.fetchTracks);
  const moveUnit        = useStore(s => s.moveUnit);
  const isMobile = useIsMobile();

  const [items,    setItems]    = useState([]);
  const [selected, setSelected] = useState(null);   // path
  const [draft,    setDraft]    = useState('');
  const [dest,     setDest]     = useState('');
  const [preview,  setPreview]  = useState(true);   // default to rendered Preview, not Edit
  const [drawerOpen, setDrawerOpen] = useState(true);  // queue drawer: open until a note is picked
  const [speed,    setSpeed]    = useState(false);   // RSVP "Speed read" replaces the note body (text sources)
  const [menuOpen, setMenuOpen] = useState(false);   // action-bar overflow (...)
  const [trackMenu, setTrackMenu] = useState(false); // "Track: X" quick selector
  const [backlogOpen, setBacklogOpen] = useState(false);
  const [compare,  setCompare]  = useState(readCompare);   // "Compare with source" (remembered)
  const [expandedUnits, setExpandedUnits] = useState(() => new Set());
  const [orient,   setOrient]   = useState(null);     // 'portrait' | 'landscape' | null
  const [picker,   setPicker]   = useState(null);
  const [vaultRoot, setVaultRoot] = useState(null);
  const [loading,  setLoading]  = useState(true);   // true until the first fetch settles (no premature empty state)
  const [busy,     setBusy]     = useState(false);
  const [error,    setError]    = useState(null);
  const [status,   setStatus]   = useState('');
  const [checked,  setChecked]  = useState(() => new Set());  // paths selected for bulk delete
  const [barOpen,  setBarOpen]  = useState(() => !isMobile);  // mobile: collapse the action bar
  // Collapsible Learn queue tree: source folders (keyed captureId) and, within a PDF
  // source, chapter subfolders (keyed `${captureId}::${chapter}`). Collapsed by default —
  // an inbox with several sources open at once shouldn't dump every note on screen at once.
  const [expandedFolders,  setExpandedFolders]  = useState(() => new Set());
  const [expandedChapters, setExpandedChapters] = useState(() => new Set());
  // Drag-drop reorg of staging mini-folders: dragPath = the note being dragged (only
  // un-captured standalone notes are draggable — capture/in-place notes are pipeline-owned);
  // dropKey = the folder/zone highlighted under the cursor. Online-only, like split.
  const [dragPath, setDragPath] = useState(null);
  const [dropKey,  setDropKey]  = useState(null);

  const current = items.find(i => i.path === selected) || null;
  const workingItem = current ? { ...current, content: draft } : null;

  // Queue grouped by source so same-source notes sit together.
  const orderedItems = useMemo(() => groupBySource(items), [items]);

  // Today = notes whose unit (captureId[/chapter]) the tracks scheduled for today; everything
  // else (incl. notes with no capture-id) is Backlog. No plan loaded → no split, plain tree.
  const unitKeys = useMemo(() => new Set((todayUnits || []).map(u => u.groupKey)), [todayUnits]);
  const todayMembers = useMemo(() => {
    const m = new Map((todayUnits || []).map(u => [u.groupKey, []]));
    for (const it of orderedItems) { const k = unitKeyOf(it); if (k && m.has(k)) m.get(k).push(it); }
    return m;
  }, [orderedItems, todayUnits]);
  const backlogItems = useMemo(
    () => (todayUnits ? orderedItems.filter(it => !unitKeys.has(unitKeyOf(it))) : orderedItems),
    [orderedItems, todayUnits, unitKeys]);

  const kind = current ? itemSourceKind(current) : null;
  const hasSource = !!kind && kind !== 'text';
  const showCompare = hasSource && compare;
  const unit = current ? (todayUnits || []).find(u => u.groupKey === unitKeyOf(current)) : null;
  const canPickTrack = !!current?.captureId && !current.inPlace;

  // Landscape video is wide → horizontal split (source on top); everything else vertical.
  const orientation = orient === 'landscape' ? 'horizontal' : 'vertical';

  const select = useCallback((item) => {
    setSelected(item.path);
    setDraft(item.content);
    setDest(item.suggestedFolder || '');
    setPreview(true);   // land on Preview each note; user opts into Edit
    setSpeed(false);
    setMenuOpen(false);
    setTrackMenu(false);
    setDrawerOpen(false);   // drawer auto-closes once a note is open
    setStatus('');
    setOrient(null);
    // Selecting a note inside a collapsed folder/chapter must reveal it — otherwise the
    // active row silently disappears (e.g. auto-advance after filing a sibling).
    if (item.captureId && !item.inPlace) {
      setExpandedFolders(s => (s.has(item.captureId) ? s : new Set(s).add(item.captureId)));
      if (item.chapter) {
        const ck = `${item.captureId}::${item.chapter}`;
        setExpandedChapters(s => (s.has(ck) ? s : new Set(s).add(ck)));
      }
    }
  }, []);

  const toggleFolder  = key => setExpandedFolders(s => { const n = new Set(s); n.has(key) ? n.delete(key) : n.add(key); return n; });
  const toggleChapter = key => setExpandedChapters(s => { const n = new Set(s); n.has(key) ? n.delete(key) : n.add(key); return n; });

  // opts.preferGroup (a source group key): after an action, auto-advance to the next note in
  // THAT group and stop (select nothing) once it's exhausted — instead of jumping to list[0].
  const load = useCallback((opts) => {
    setLoading(true);
    setError(null);
    fetchInbox()
      .then(list => {
        setItems(list);
        onCount?.(list.length);
        if (!list.length) { setSelected(null); setDraft(''); setDest(''); return; }
        if (list.some(i => i.path === selected)) return;   // current still present → keep it
        if (opts?.preferGroup !== undefined) {
          // Same group first; once exhausted continue into the next group (first in review order).
          select(nextInGroup(list, opts.preferGroup) || groupBySource(list)[0] || list[0]);
          return;
        }
        // initial load / generic refresh: pick nothing — the drawer stays open so the user
        // chooses from Today (note-first, but the choice is theirs).
      })
      .catch(() => setError('Could not load the inbox.'))
      .finally(() => setLoading(false));
  }, [selected, onCount, select]);

  useEffect(() => {
    if (!isAuthenticated) return;
    load();
    Promise.allSettled([fetchTodayPlan(), fetchTracks()]);
    fetchChildren(null)
      .then(r => { setVaultRoot(r.parentPath); setMeta('cachedVaultRoot', r.parentPath).catch(() => {}); })
      .catch(() => { getMeta('cachedVaultRoot').then(setVaultRoot).catch(() => {}); });
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isAuthenticated]);

  // ── folder tree picker (reuses FolderPicker, browsing the vault) ──────────────
  // Offline (or the server unreachable): fall back to deriving the tree from the note-path
  // list already cached for offline search/linking (utils/offlineFolders.js) — same
  // absolute-path shape /children uses, so a folder picked offline files identically to one
  // picked online.
  const loadVaultDir = useCallback(async (path) => {
    try {
      const r = await fetchChildren(path);
      const cur = r.parentPath;
      const parent = (!vaultRoot || cur === vaultRoot) ? null : dirName(cur);
      return {
        current: cur,
        parent,
        dirs: (r.folderPaths || [])
          .filter(f => !/[/\\]_inbox$/.test(f))
          .map(p => ({ path: p, name: baseName(p) })),
      };
    } catch {
      const names = (await getMeta('cachedNoteNames').catch(() => null)) || [];
      return offlineChildrenOf(names, path, vaultRoot);
    }
  }, [vaultRoot]);

  function openFolderPicker() {
    setPicker({
      title: 'File into folder',
      loadPath: loadVaultDir,
      initialPath: dest || null,
      confirmLabel: 'Choose this folder',
      onSelect: (p) => { setDest(p); setPicker(null); },
      onClose: () => setPicker(null),
    });
  }

  // ── file a whole folder/chapter node in one shot ──────────────────────────────
  // Loops the SAME per-note file() action the single-note bar uses (fileInboxNote —
  // already offline-aware via offlineApi) for every member, into one shared destination:
  // <picked base>/<source title>[/<chapter>]. No new backend endpoint — filing a group
  // is just filing its notes into the same place, one at a time; a bad note in the
  // group must not block the rest (same "one bad note" tolerance as the ingest pipeline).
  const segClean = s => (s || '').replace(/[\\/]+/g, '-').trim() || 'Untitled';

  async function fileGroup(items, base, segments) {
    if (!items.length) return;
    const target = [base.replace(/\/+$/, ''), ...segments.map(segClean)].join('/');
    const group = inboxGroupKey(items[0]);
    setBusy(true); setStatus(`Filing ${items.length} note(s)…`);
    let failed = 0;
    for (const it of items) {
      const content = it.path === selected ? draft : it.content;
      try { await fileInboxNote(it.path, target, content); }
      catch { failed++; }
    }
    markLearnTaskDone();
    setStatus(failed ? `Filed with ${failed} error(s).` : '');
    setBusy(false);
    load({ preferGroup: group });
  }

  function openFolderFilePicker(folder) {
    setPicker({
      title: `File "${folder.title}" into…`,
      loadPath: loadVaultDir,
      initialPath: folder.suggestedFolder || null,
      confirmLabel: 'File here',
      onSelect: (base) => { setPicker(null); fileGroup(folderAllItems(folder), base, [folder.title]); },
      onClose: () => setPicker(null),
    });
  }

  function openChapterFilePicker(folder, chapter) {
    setPicker({
      title: `File "${folder.title} / ${chapter.label}" into…`,
      loadPath: loadVaultDir,
      initialPath: chapter.suggestedFolder || folder.suggestedFolder || null,
      confirmLabel: 'File here',
      onSelect: (base) => { setPicker(null); fileGroup(chapter.items, base, [folder.title, chapter.label]); },
      onClose: () => setPicker(null),
    });
  }

  // ── actions ───────────────────────────────────────────────────────────────────
  async function file() {
    if (!current) return;
    if (!dest.trim()) { setStatus('Pick a destination folder.'); return; }
    const group = inboxGroupKey(current);
    setBusy(true); setStatus('Filing…');
    try { await fileInboxNote(current.path, dest.trim(), draft); markLearnTaskDone(); setStatus(''); load({ preferGroup: group }); }
    catch (e) { setStatus(`Failed: ${e.message || e}`); }
    finally { setBusy(false); }
  }

  // Enter = Save & file (Save & acknowledge for in-place notes). Ignored while typing in a field,
  // on a focused button/link (Enter already activates it), with a modal open, or mid-action.
  // Ctrl/Cmd+Enter works from inside the editor too. Latest closure kept in a ref so the
  // window listener is bound once.
  const enterRef = useRef(null);
  enterRef.current = () => {
    if (!current || busy || picker) return;
    if (current.inPlace) acknowledge(); else file();
  };
  useEffect(() => {
    function onKey(e) {
      if (e.key !== 'Enter' || e.shiftKey || e.altKey || e.isComposing) return;
      const t = e.target;
      const tag = t?.tagName;
      const typing = tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' || t?.isContentEditable;
      if (typing && !(e.ctrlKey || e.metaKey)) return;
      // Queue-row buttons keep focus after a click, so Enter there must still file.
      if (!typing && (tag === 'BUTTON' || tag === 'A') && !t.closest('[data-queue-row]')) return;
      e.preventDefault();
      enterRef.current?.();
    }
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, []);

  // Popovers (overflow menu, track selector): Escape or an outside click closes them.
  useEffect(() => {
    if (!menuOpen && !trackMenu) return;
    const close = () => { setMenuOpen(false); setTrackMenu(false); };
    const onKey = e => { if (e.key === 'Escape') close(); };
    const onDown = e => { if (!e.target.closest?.('[data-popover]')) close(); };
    window.addEventListener('keydown', onKey);
    window.addEventListener('mousedown', onDown);
    return () => { window.removeEventListener('keydown', onKey); window.removeEventListener('mousedown', onDown); };
  }, [menuOpen, trackMenu]);

  function toggleCompare() {
    setCompare(c => { const n = !c; writeCompare(n); return n; });
  }

  async function pickTrack(trackId) {
    setTrackMenu(false);
    if (!current?.captureId) return;
    try { await moveUnit(current.captureId, unitChapterOf(current), trackId); setStatus(''); }
    catch (e) { setStatus(`Couldn't move: ${e.message || e}`); }
  }

  async function acknowledge() {
    if (!current) return;
    const group = inboxGroupKey(current);
    setBusy(true); setStatus('Saving…');
    try {
      // Best-effort: offline this hits no server — the acknowledge still queues. (Filing
      // carries edited content in its event; in-place edits offline are the rare gap.)
      if (draft !== current.content) { try { await updateNote(current.path, draft); } catch {} }
      await acknowledgeCapture(current.captureId);
      markLearnTaskDone();
      setStatus(''); load({ preferGroup: group });
    } catch (e) { setStatus(`Failed: ${e.message || e}`); }
    finally { setBusy(false); }
  }

  async function discard() {
    if (!current) return;
    const group = inboxGroupKey(current);
    setBusy(true);
    try { await discardInboxNote(current.path); load({ preferGroup: group }); }
    catch (e) { setStatus(`Failed: ${e.message || e}`); }
    finally { setBusy(false); }
  }

  // Split: spawn an additional note for the SAME source region, slotted right after this one
  // (#N → #N-1). The backend duplicates the on-disk note, so persist any unsaved edits first;
  // the new note lands selected so the user can trim it down to its own chapter.
  async function split() {
    if (!current) return;
    setBusy(true); setStatus('Splitting…');
    try {
      if (draft !== current.content) { try { await updateNote(current.path, draft); } catch {} }
      const { path } = await splitInboxNote(current.path);
      setStatus('');
      setSelected(path);   // load() will select this new note once it's in the list
      load();
    } catch (e) { setStatus(`Failed: ${e.message || e}`); }
    finally { setBusy(false); }
  }

  // ── drag-drop reorg (move staged notes between mini-folders) ─────────────────
  // Only un-captured standalone notes drag — capture/in-place notes are pipeline-owned and
  // their on-disk folder is meaningful (source/chapter). Targets: a mini-folder header,
  // the "Inbox root" zone, or "New folder…". All online-only (offline the move endpoint is
  // unreachable — surfaces as a status, same as split). `folder` is relative to _inbox.
  const isDraggable = it => !it.inPlace && !it.captureId;

  async function moveTo(path, folder) {
    if (!path) return;
    setBusy(true); setStatus('Moving…');
    try { await moveInboxNote(path, folder); setStatus(''); load(); }
    catch (e) { setStatus(`Move failed: ${e.message || e}`); }
    finally { setBusy(false); setDragPath(null); setDropKey(null); }
  }

  async function newFolderFromDrag(path) {
    const name = window.prompt('New mini-folder name');
    setDragPath(null); setDropKey(null);
    if (!name || !name.trim()) return;
    setBusy(true); setStatus('Creating folder…');
    try {
      await createInboxFolder(name.trim());
      if (path) await moveInboxNote(path, name.trim());
      setStatus(''); load();
    } catch (e) { setStatus(`Failed: ${e.message || e}`); }
    finally { setBusy(false); }
  }

  const onDropTo = (folder) => (e) => { e.preventDefault(); moveTo(dragPath, folder); };
  const allowDrop = (key) => (e) => { e.preventDefault(); if (dropKey !== key) setDropKey(key); };

  // ── bulk delete (email-style multi-select) ──────────────────────────────────
  // Only standalone _inbox notes can be discarded (in-place notes are acknowledged, not
  // deleted). Deleting a source's LAST note trashes its media too (backend Stage 4 retention).
  const deletable = orderedItems.filter(i => !i.inPlace).map(i => i.path);
  const allChecked = deletable.length > 0 && deletable.every(p => checked.has(p));

  const toggleOne = (path) => setChecked(s => {
    const n = new Set(s); n.has(path) ? n.delete(path) : n.add(path); return n;
  });
  const toggleAll = () => setChecked(allChecked ? new Set() : new Set(deletable));

  async function deleteSelected() {
    if (!checked.size) return;
    if (!window.confirm(
      `Delete ${checked.size} note(s)? A source whose notes are all deleted is cleaned up too.`)) return;
    setBusy(true); setStatus(`Deleting ${checked.size}…`);
    let failed = 0;
    for (const p of checked) {
      try { await discardInboxNote(p); } catch { failed++; }
    }
    setChecked(new Set());
    setStatus(failed ? `Deleted with ${failed} error(s).` : '');
    setBusy(false);
    load();
  }

  // ── queue rendering ──────────────────────────────────────────────────────────
  function renderNoteRow(it, depth) {
    const drag = isDraggable(it);
    return (
      <div key={it.path}
           className={`${styles.rowWrap} ${drag ? styles.rowDraggable : ''} ${dragPath === it.path ? styles.dragging : ''}`}
           style={{ '--row-depth': depth }}
           draggable={drag}
           onDragStart={drag ? (e => { setDragPath(it.path); e.dataTransfer.effectAllowed = 'move'; }) : undefined}
           onDragEnd={drag ? (() => { setDragPath(null); setDropKey(null); }) : undefined}>
        {!it.inPlace && (
          <Checkbox className={styles.rowCheck}
                    checked={checked.has(it.path)} onChange={() => toggleOne(it.path)}
                    aria-label={`Select ${it.title} for delete`} />
        )}
        <button
          className={`${styles.row} ${selected === it.path ? styles.rowActive : ''}`}
          onClick={() => select(it)} title={it.title} data-queue-row>
          <span className={styles.rowTitle}>{it.title}</span>
          {it.inPlace
            ? <span className={styles.rowTag}>in place</span>
            : captureLabel(it) && <span className={styles.rowTag}>{captureLabel(it)}</span>}
        </button>
      </div>
    );
  }

  function renderFolderHeader({ key, title, count, expanded, onToggle, suggestedFolder, onFile, depth, dropFolder }) {
    // dropFolder (relative _inbox path) set → this header is a drag-drop target for reorg.
    const droppable = dropFolder != null && dragPath;
    return (
      <div key={key}
           className={`${styles.folderRow} ${dropKey === key ? styles.dropTarget : ''}`}
           style={{ '--row-depth': depth }}
           onDragOver={droppable ? allowDrop(key) : undefined}
           onDragLeave={droppable ? (() => setDropKey(k => k === key ? null : k)) : undefined}
           onDrop={droppable ? onDropTo(dropFolder) : undefined}>
        <button className={styles.folderToggle} onClick={onToggle} aria-expanded={expanded}
                title={title}>
          <span className={styles.folderChevron} aria-hidden="true">{expanded ? '▾' : '▸'}</span>
          <span className={styles.folderTitle}>{title}</span>
          <span className={styles.folderCount}>{count}</span>
        </button>
        <button className={styles.folderFileBtn} onClick={onFile}
                title={suggestedFolder ? `Proposed: ${suggestedFolder}` : 'File this folder'}>
          <Icon name="folder" size={14} />{suggestedFolder ? ` ${baseName(suggestedFolder)}` : ''}
        </button>
      </div>
    );
  }

  // Backlog (and the no-plan fallback): source folders (count), PDF sources nest chapter
  // subfolders — both collapsible, both carry the "file the whole thing here?" pre-pick from
  // the group-centroid classifier. in-place notes / capture-less notes render as plain rows.
  function renderTree(list) {
    const tree = buildInboxTree(list);
    const out = [];
    for (const node of tree) {
      if (node.type === 'leaf') { out.push(renderNoteRow(node.item, 0)); continue; }
      const expanded = expandedFolders.has(node.key);
      out.push(renderFolderHeader({
        key: node.key, title: node.title, count: folderAllItems(node).length, expanded,
        onToggle: () => toggleFolder(node.key), suggestedFolder: node.suggestedFolder,
        onFile: () => openFolderFilePicker(node), depth: 0,
        dropFolder: node.mcp ? node.folderPath : undefined,
      }));
      if (!expanded) continue;
      for (const it of node.items) out.push(renderNoteRow(it, 1));
      for (const ch of node.chapters) {
        const chExpanded = expandedChapters.has(ch.key);
        out.push(renderFolderHeader({
          key: ch.key, title: ch.label, count: ch.items.length, expanded: chExpanded,
          onToggle: () => toggleChapter(ch.key), suggestedFolder: ch.suggestedFolder,
          onFile: () => openChapterFilePicker(node, ch), depth: 1,
        }));
        if (!chExpanded) continue;
        for (const it of ch.items) out.push(renderNoteRow(it, 2));
      }
    }
    return out;
  }

  const toggleUnit = key => setExpandedUnits(s => { const n = new Set(s); n.has(key) ? n.delete(key) : n.add(key); return n; });

  function renderToday() {
    return (todayUnits || []).map(u => {
      const members = todayMembers.get(u.groupKey) || [];
      if (!members.length) return null;   // finished / not in the inbox right now
      const open = expandedUnits.has(u.groupKey) || members.some(m => m.path === selected);
      return (
        <div key={u.groupKey} data-testid="today-unit">
          <div className={styles.folderRow}>
            <button className={styles.folderToggle} onClick={() => toggleUnit(u.groupKey)}
                    aria-expanded={open} title={u.title}>
              <span className={styles.folderChevron} aria-hidden="true">{open ? '▾' : '▸'}</span>
              <span className={styles.folderTitle}>{u.title}</span>
              <span className={styles.folderCount}>{members.length}</span>
            </button>
            {u.trackTitle && <span className={styles.unitTrack}>{u.trackTitle}</span>}
          </div>
          {open && members.map(it => renderNoteRow(it, 1))}
        </div>
      );
    });
  }

  // ── panels ──────────────────────────────────────────────────────────────────
  const sourcePanel = <SourceSplicePanel item={workingItem} onOrientation={setOrient} />;
  const currentTrack = unit?.trackTitle;

  const notePanel = (
    <div className={styles.notePanel}>
      <div className={styles.noteHead}>
        <button className={styles.drawerBtn} onClick={() => setDrawerOpen(o => !o)}
                aria-expanded={drawerOpen} aria-label={drawerOpen ? 'Hide queue' : 'Show queue'}
                title={drawerOpen ? 'Hide queue' : 'Show queue'}>
          <Icon name="folder" size={16} />
        </button>
        <span className={styles.noteTitle} title={current?.title}>{current?.title || 'New note'}</span>
        {canPickTrack && (
          <span className={styles.popWrap} data-popover>
            <Button size="sm" variant="ghost" aria-haspopup="menu" aria-expanded={trackMenu}
                    onClick={() => { setTrackMenu(o => !o); setMenuOpen(false); }}>
              {currentTrack ? `Track: ${currentTrack}` : 'Track'} ▾
            </Button>
            {trackMenu && (
              <div className={styles.popover} role="menu" aria-label="Move to track">
                {tracks.length === 0 && <span className={styles.popEmpty}>No tracks yet</span>}
                {tracks.map(t => (
                  <button key={t.id} role="menuitem" className={styles.popItem}
                          aria-current={unit?.trackId === t.id || undefined}
                          onClick={() => pickTrack(t.id)}>{t.title}</button>
                ))}
              </div>
            )}
          </span>
        )}
        {hasSource && (
          <Button size="sm" variant="ghost" aria-pressed={compare} onClick={toggleCompare}>
            Compare with source
          </Button>
        )}
        {!hasSource && kind && !speed && (
          <Button size="sm" variant="ghost" onClick={() => setSpeed(true)}>Speed read</Button>
        )}
        {!speed && (
          <SegmentedTabs
            aria-label="Note view"
            tabs={[{ id: 'edit', label: 'Edit' }, { id: 'preview', label: 'Preview' }]}
            value={preview ? 'preview' : 'edit'}
            onChange={id => setPreview(id === 'preview')}
          />
        )}
      </div>
      {speed
        ? <div className={styles.speedBox}>
            <Button size="sm" variant="ghost" onClick={() => setSpeed(false)}>Back</Button>
            <RsvpReader text={draft} />
          </div>
        : preview
          ? <div className={styles.previewBox}>
              <div className={styles.column}>
                <MarkdownView content={stripLeadingTitle(draft, current?.title)} resetKey={selected} />
              </div>
            </div>
          : <textarea className={styles.editor} value={draft}
                      onChange={e => setDraft(e.target.value)} spellCheck={false} />}
    </div>
  );

  const queueBody = (
    <div className={styles.queueList}>
      {loading && items.length === 0 && <Skeleton lines={5} height={32} />}
      {error && <ErrorState message="Couldn't load the inbox" onRetry={() => load()} />}
      {!loading && !error && items.length === 0 && (
        <p className={styles.status}>Inbox is empty. Capture a resource and its proposed notes appear here.</p>
      )}
      {deletable.length > 0 && (
        <div className={styles.bulkBar}>
          <Checkbox label="Select all" checked={allChecked} onChange={toggleAll} />
          {checked.size > 0 && (
            <Button size="sm" variant="danger" onClick={deleteSelected} disabled={busy}>
              Delete {checked.size}
            </Button>
          )}
        </div>
      )}
      {todayUnits ? (
        <>
          <div className={styles.sectionHead}>Today</div>
          {renderToday()}
          {items.length > 0 && !Array.from(todayMembers.values()).some(m => m.length) && (
            <p className={styles.status}>Nothing scheduled from the inbox today.</p>
          )}
          {backlogItems.length > 0 && (
            <>
              <button className={styles.sectionToggle} aria-expanded={backlogOpen}
                      onClick={() => setBacklogOpen(o => !o)}>
                <span className={styles.folderChevron} aria-hidden="true">{backlogOpen ? '▾' : '▸'}</span>
                Backlog ({backlogItems.length})
              </button>
              {backlogOpen && renderTree(backlogItems)}
            </>
          )}
        </>
      ) : renderTree(orderedItems)}
    </div>
  );

  const compareLayout = showCompare && current;

  return (
    <div className={styles.review}>
      {drawerOpen && isMobile && <div className={styles.scrim} onClick={() => setDrawerOpen(false)} />}
      {drawerOpen && <aside className={styles.queue} aria-label="Queue">
        <div className={styles.queueHead}>
          <span className={styles.queueTitle}>Queue</span>
          {current && (
            <button className={styles.collapseBtn} onClick={() => setDrawerOpen(false)}
                    aria-label="Hide queue" title="Hide queue">‹</button>
          )}
        </div>
        {queueBody}
      </aside>}

      {current ? (
        <div className={styles.main}>
          <div className={styles.splitArea}>
            {compareLayout
              ? <LearnLayout orientation={orientation} slotA={sourcePanel} slotB={notePanel} labelA="Source" labelB="Note" />
              : notePanel}
          </div>

          <div className={`${styles.bottomBar} ${barOpen ? '' : styles.bottomBarCollapsed}`}>
            <button
              className={styles.barToggle}
              onClick={() => setBarOpen(o => !o)}
              aria-expanded={barOpen}
              title={barOpen ? 'Hide actions' : 'Show actions'}
            >
              <span className={styles.barToggleLabel}>
                {current.inPlace ? 'Actions' : <><Icon name="folder" size={14} /> {dest ? baseName(dest) : 'No folder'}</>}
              </span>
              <span aria-hidden="true">{barOpen ? '▾' : '▸'}</span>
            </button>

            {barOpen && (
              <div className={styles.barControls}>
                {current.inPlace ? (
                  <Button variant="primary" onClick={acknowledge} disabled={busy} title="Enter">Save &amp; acknowledge</Button>
                ) : (
                  <>
                    <Button variant="primary" onClick={file} disabled={busy} title="Enter">Save &amp; file</Button>
                    <Button className={styles.folderBtn} icon="folder" onClick={openFolderPicker} title={dest}>
                      {dest || 'Choose a folder'}
                    </Button>
                    <span className={styles.popWrap} data-popover>
                      <Button variant="ghost" aria-label="More actions" aria-haspopup="menu" aria-expanded={menuOpen}
                              onClick={() => { setMenuOpen(o => !o); setTrackMenu(false); }}>⋯</Button>
                      {menuOpen && (
                        <div className={`${styles.popover} ${styles.popoverUp}`} role="menu" aria-label="More actions">
                          <button role="menuitem" className={styles.popItem} disabled={busy}
                                  onClick={() => { setMenuOpen(false); split(); }}
                                  title="Create another note for the same source region (splits this one into chapters)">Split</button>
                          <button role="menuitem" className={`${styles.popItem} ${styles.popDanger}`} disabled={busy}
                                  onClick={() => { setMenuOpen(false); discard(); }}>Discard</button>
                        </div>
                      )}
                    </span>
                  </>
                )}
                {status && <span className={styles.barStatus}>{status}</span>}
              </div>
            )}
          </div>
        </div>
      ) : (
        <div className={styles.emptyMain}>
          {loading ? <Skeleton lines={3} height={32} /> : error ? null : (
            <>
              <EmptyState icon="inbox" title={items.length ? 'Pick a note to review' : 'Nothing to review'}
                          description={items.length ? undefined : 'Captured resources show up here as proposed notes.'} />
              {items.length > 0 && !drawerOpen && (
                <Button variant="secondary" onClick={() => setDrawerOpen(true)}>Show queue</Button>
              )}
            </>
          )}
        </div>
      )}

      {picker && <FolderPicker {...picker} />}
    </div>
  );
}
