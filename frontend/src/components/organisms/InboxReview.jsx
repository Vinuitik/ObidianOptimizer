import { useState, useEffect, useCallback, useMemo } from 'react';
import { fetchInbox, fileInboxNote, discardInboxNote, acknowledgeCapture } from '../../api/inbox';
import { fetchChildren, updateNote } from '../../api/notes';
import { buildSourceColors, groupBySource } from '../../utils/sourceColor';
import useStore from '../../store/useStore';
import LearnLayout from '../templates/LearnLayout';
import NoteRenderer from '../molecules/NoteRenderer';
import SourceSplicePanel from './SourceSplicePanel';
import FolderPicker from './FolderPicker';
import styles from './InboxReview.module.css';

const baseName = p => (p || '').replace(/[/\\]+$/, '').split(/[/\\]/).pop() || p;
const dirName  = p => p.replace(/[/\\]+$/, '').replace(/[/\\][^/\\]*$/, '');

// Ingest review (INGESTION_V2_FLOWS §7). Reuses the Library layout the user liked:
//   [collapsible queue] · LearnLayout( ORIGINAL source | NEW note ) · [proposed folder bar]
// LearnLayout brings the adjustable, swappable, orientation-aware split for free (landscape
// video → horizontal, else vertical). The source panel is read-only ("original"); the note
// is editable ("new"). The destination folder is PROPOSED (find_home) and re-picked from an
// animated folder tree (FolderPicker). Filing moves the note into its folder + FSRS queue.
export default function InboxReview({ onCount }) {
  const isAuthenticated = useStore(s => s.isAuthenticated);

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

  const load = useCallback(() => {
    setLoading(true);
    setError(null);
    fetchInbox()
      .then(list => {
        setItems(list);
        onCount?.(list.length);
        if (list.length && !list.some(i => i.path === selected)) select(list[0]);
        if (!list.length) { setSelected(null); setDraft(''); setDest(''); }
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
    setBusy(true); setStatus('Filing…');
    try { await fileInboxNote(current.path, dest.trim(), draft); setStatus(''); load(); }
    catch (e) { setStatus(`Failed: ${e.message || e}`); }
    finally { setBusy(false); }
  }

  async function acknowledge() {
    if (!current) return;
    setBusy(true); setStatus('Saving…');
    try {
      if (draft !== current.content) await updateNote(current.path, draft);
      await acknowledgeCapture(current.captureId);
      setStatus(''); load();
    } catch (e) { setStatus(`Failed: ${e.message || e}`); }
    finally { setBusy(false); }
  }

  async function discard() {
    if (!current) return;
    setBusy(true);
    try { await discardInboxNote(current.path); load(); }
    catch (e) { setStatus(`Failed: ${e.message || e}`); }
    finally { setBusy(false); }
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
            {orderedItems.map(it => {
              const color = sourceColors.get(it.captureId || it.path);
              return (
                <button key={it.path}
                  className={`${styles.row} ${selected === it.path ? styles.rowActive : ''}`}
                  style={color ? { '--row-src': color } : undefined}
                  onClick={() => select(it)} title={it.title}>
                  {color && <span className={styles.srcDot} aria-hidden="true" />}
                  <span className={styles.rowTitle}>{it.title}</span>
                  {it.inPlace
                    ? <span className={styles.rowTag}>in place</span>
                    : it.captureSeq != null && <span className={styles.rowTag}>#{it.captureSeq + 1}</span>}
                </button>
              );
            })}
          </div>
        )}
      </aside>

      {current ? (
        <div className={styles.main}>
          <div className={styles.splitArea}>
            <LearnLayout orientation={orientation} slotA={sourcePanel} slotB={notePanel} />
          </div>

          <div className={styles.bottomBar}>
            {current.inPlace ? (
              <button className={styles.primary} onClick={acknowledge} disabled={busy}>Save &amp; acknowledge</button>
            ) : (
              <>
                <span className={styles.barLabel}>Proposed folder</span>
                <button className={styles.folderBtn} onClick={openFolderPicker} title={dest}>
                  📁 {dest || 'Choose a folder'} ▸
                </button>
                <button className={styles.primary} onClick={file} disabled={busy}>Save &amp; file</button>
                <button className={styles.ghost} onClick={discard} disabled={busy}>Discard</button>
              </>
            )}
            {status && <span className={styles.barStatus}>{status}</span>}
          </div>
        </div>
      ) : (
        <div className={styles.emptyMain}><p>Select a note to review.</p></div>
      )}

      {picker && <FolderPicker {...picker} />}
    </div>
  );
}
