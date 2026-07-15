import { useState, useEffect, useCallback, useMemo } from 'react';
import {
  fetchInboxOffline as fetchInbox,
  fileInboxOffline as fileInboxNote,
  discardInboxOffline as discardInboxNote,
  acknowledgeOffline as acknowledgeCapture,
} from '../../pwa/offlineApi';
import { fetchChildren, updateNote } from '../../api/notes';
import { splitInboxNote } from '../../api/inbox';
import { buildSourceColors, groupBySource, captureLabel } from '../../utils/sourceColor';
import { markLearnTaskDone } from '../../utils/dailyDuty';
import useStore from '../../store/useStore';
import { useIsMobile } from '../../utils/useMediaQuery';
import LearnLayout from '../templates/LearnLayout';
import NoteRenderer from '../molecules/NoteRenderer';
import SourceSplicePanel from './SourceSplicePanel';
import FolderPicker from './FolderPicker';
import styles from './InboxReview.module.css';

const baseName = p => (p || '').replace(/[/\\]+$/, '').split(/[/\\]/).pop() || p;
const dirName  = p => p.replace(/[/\\]+$/, '').replace(/[/\\][^/\\]*$/, '');

// A note's source group (same key groupBySource / buildSourceColors use).
const groupKeyOf = it => it?.captureId || it?.path;

// After acting on a note, the next one to review WITHIN its source group: the first remaining
// group member in review order (groupBySource). The acted note is already gone from `list`, so
// what's left flows in reading order. null → the group is exhausted (caller then auto-picks
// nothing, per the "don't jump to an unrelated source" rule).
function nextInGroup(list, groupKey) {
  const inGroup = groupBySource(list).filter(i => groupKeyOf(i) === groupKey);
  return inGroup[0] || null;
}

// Ingest review (INGESTION_V2_FLOWS §7). Reuses the Library layout the user liked:
//   [collapsible queue] · LearnLayout( ORIGINAL source | NEW note ) · [proposed folder bar]
// LearnLayout brings the adjustable, swappable, orientation-aware split for free (landscape
// video → horizontal, else vertical). The source panel is read-only ("original"); the note
// is editable ("new"). The destination folder is PROPOSED (find_home) and re-picked from an
// animated folder tree (FolderPicker). Filing moves the note into its folder + FSRS queue.
export default function InboxReview({ onCount }) {
  const isAuthenticated = useStore(s => s.isAuthenticated);
  const isMobile = useIsMobile();

  const [items,    setItems]    = useState([]);
  const [selected, setSelected] = useState(null);   // path
  const [draft,    setDraft]    = useState('');
  const [dest,     setDest]     = useState('');
  const [preview,  setPreview]  = useState(true);   // default to rendered Preview, not Edit
  const [collapsed, setCollapsed] = useState(false);  // queue rail
  const [orient,   setOrient]   = useState(null);     // 'portrait' | 'landscape' | null
  const [picker,   setPicker]   = useState(null);
  const [vaultRoot, setVaultRoot] = useState(null);
  const [loading,  setLoading]  = useState(false);
  const [busy,     setBusy]     = useState(false);
  const [error,    setError]    = useState(null);
  const [status,   setStatus]   = useState('');
  const [checked,  setChecked]  = useState(() => new Set());  // paths selected for bulk delete
  const [barOpen,  setBarOpen]  = useState(() => !isMobile);  // mobile: collapse the action bar

  const current = items.find(i => i.path === selected) || null;
  const workingItem = current ? { ...current, content: draft } : null;

  // Queue grouped by source so same-source notes sit together as a color band, each source
  // a well-spaced hue (golden-angle) so adjacent groups never look alike.
  const orderedItems = useMemo(() => groupBySource(items), [items]);
  const sourceColors = useMemo(() => buildSourceColors(orderedItems), [orderedItems]);

  // Landscape video is wide → horizontal split (source on top); everything else vertical.
  const orientation = orient === 'landscape' ? 'horizontal' : 'vertical';

  const select = useCallback((item) => {
    setSelected(item.path);
    setDraft(item.content);
    setDest(item.suggestedFolder || '');
    setPreview(true);   // land on Preview each note; user opts into Edit
    setStatus('');
    setOrient(null);
  }, []);

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
          const next = nextInGroup(list, opts.preferGroup);
          if (next) select(next);
          else { setSelected(null); setDraft(''); setDest(''); }
          return;
        }
        select(list[0]);   // initial load / generic refresh
      })
      .catch(() => setError('Could not load the inbox.'))
      .finally(() => setLoading(false));
  }, [selected, onCount, select]);

  useEffect(() => {
    if (!isAuthenticated) return;
    load();
    fetchChildren(null).then(r => setVaultRoot(r.parentPath)).catch(() => {});
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isAuthenticated]);

  // ── folder tree picker (reuses FolderPicker, browsing the vault) ──────────────
  const loadVaultDir = useCallback(async (path) => {
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

  // ── actions ───────────────────────────────────────────────────────────────────
  async function file() {
    if (!current) return;
    if (!dest.trim()) { setStatus('Pick a destination folder.'); return; }
    const group = groupKeyOf(current);
    setBusy(true); setStatus('Filing…');
    try { await fileInboxNote(current.path, dest.trim(), draft); markLearnTaskDone(); setStatus(''); load({ preferGroup: group }); }
    catch (e) { setStatus(`Failed: ${e.message || e}`); }
    finally { setBusy(false); }
  }

  async function acknowledge() {
    if (!current) return;
    const group = groupKeyOf(current);
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
    const group = groupKeyOf(current);
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

  // ── panels ──────────────────────────────────────────────────────────────────
  const sourcePanel = <SourceSplicePanel item={workingItem} onOrientation={setOrient} />;

  const notePanel = (
    <div className={styles.notePanel}>
      <div className={styles.noteHead}>
        <span className={styles.noteTag}>New note {current?.inPlace ? '· in place' : '· editable'}</span>
        <div className={styles.tabs}>
          <button className={`${styles.tab} ${!preview ? styles.tabOn : ''}`} onClick={() => setPreview(false)}>Edit</button>
          <button className={`${styles.tab} ${preview ? styles.tabOn : ''}`} onClick={() => setPreview(true)}>Preview</button>
        </div>
      </div>
      {preview
        ? <div className={styles.previewBox}><NoteRenderer content={draft} resetKey={selected} /></div>
        : <textarea className={styles.editor} value={draft}
                    onChange={e => setDraft(e.target.value)} spellCheck={false} />}
    </div>
  );

  return (
    <div className={styles.review}>
      <aside className={`${styles.queue} ${collapsed ? styles.queueCollapsed : ''}`}>
        <button className={styles.collapseBtn} onClick={() => setCollapsed(c => !c)}
                title={collapsed ? 'Expand list' : 'Collapse list'}>
          {collapsed ? '☰' : '‹'}
        </button>
        {!collapsed && (
          <div className={styles.queueList}>
            {loading && <p className={styles.status}>Loading…</p>}
            {error   && <p className={styles.statusError}>{error}</p>}
            {!loading && !error && items.length === 0 && (
              <p className={styles.status}>Inbox is empty. Capture a resource and its proposed notes appear here.</p>
            )}
            {deletable.length > 0 && (
              <div className={styles.bulkBar}>
                <label className={styles.selAll}>
                  <input type="checkbox" checked={allChecked} onChange={toggleAll}
                         ref={el => { if (el) el.indeterminate = checked.size > 0 && !allChecked; }} />
                  Select all
                </label>
                {checked.size > 0 && (
                  <button className={styles.bulkDel} onClick={deleteSelected} disabled={busy}>
                    🗑 Delete {checked.size}
                  </button>
                )}
              </div>
            )}
            {orderedItems.map(it => {
              const color = sourceColors.get(it.captureId || it.path);
              return (
                <div key={it.path} className={styles.rowWrap}
                     style={color ? { '--row-src': color } : undefined}>
                  {!it.inPlace && (
                    <input type="checkbox" className={styles.rowCheck}
                           checked={checked.has(it.path)} onChange={() => toggleOne(it.path)}
                           aria-label={`Select ${it.title} for delete`} />
                  )}
                  <button
                    className={`${styles.row} ${selected === it.path ? styles.rowActive : ''}`}
                    onClick={() => select(it)} title={it.title}>
                    {color && <span className={styles.srcDot} aria-hidden="true" />}
                    <span className={styles.rowTitle}>{it.title}</span>
                    {it.inPlace
                      ? <span className={styles.rowTag}>in place</span>
                      : captureLabel(it) && <span className={styles.rowTag}>{captureLabel(it)}</span>}
                  </button>
                </div>
              );
            })}
          </div>
        )}
      </aside>

      {current ? (
        <div className={styles.main}>
          <div className={styles.splitArea}>
            <LearnLayout orientation={orientation} slotA={sourcePanel} slotB={notePanel} labelA="Source" labelB="Note" />
          </div>

          <div className={`${styles.bottomBar} ${barOpen ? '' : styles.bottomBarCollapsed}`}>
            <button
              className={styles.barToggle}
              onClick={() => setBarOpen(o => !o)}
              aria-expanded={barOpen}
              title={barOpen ? 'Hide actions' : 'Show actions'}
            >
              <span className={styles.barToggleLabel}>
                {current.inPlace ? 'Actions' : `📁 ${dest ? baseName(dest) : 'No folder'}`}
              </span>
              <span aria-hidden="true">{barOpen ? '▾' : '▸'}</span>
            </button>

            {barOpen && (
              <div className={styles.barControls}>
                {current.inPlace ? (
                  <button className={styles.primary} onClick={acknowledge} disabled={busy}>Save &amp; acknowledge</button>
                ) : (
                  <>
                    <span className={styles.barLabel}>Proposed folder</span>
                    <button className={styles.folderBtn} onClick={openFolderPicker} title={dest}>
                      📁 {dest || 'Choose a folder'} ▸
                    </button>
                    <button className={styles.primary} onClick={file} disabled={busy}>Save &amp; file</button>
                    <button className={styles.ghost} onClick={split} disabled={busy}
                            title="Create another note for the same source region (splits this one into chapters)">Split</button>
                    <button className={styles.ghost} onClick={discard} disabled={busy}>Discard</button>
                  </>
                )}
                {status && <span className={styles.barStatus}>{status}</span>}
              </div>
            )}
          </div>
        </div>
      ) : (
        <div className={styles.emptyMain}><p>Select a note to review.</p></div>
      )}

      {picker && <FolderPicker {...picker} />}
    </div>
  );
}
