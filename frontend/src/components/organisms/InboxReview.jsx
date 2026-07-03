import { useState, useEffect, useCallback } from 'react';
import { fetchInbox, fileInboxNote, discardInboxNote, acknowledgeCapture } from '../../api/inbox';
import { fetchChildren, updateNote } from '../../api/notes';
import useStore from '../../store/useStore';
import MarkdownContent from '../molecules/MarkdownContent';
import SourceSplicePanel from './SourceSplicePanel';
import LinksPanel from './LinksPanel';
import styles from './InboxReview.module.css';

// Three-panel ingest review (INGESTION_V2_FLOWS §7): a queue rail, then
//   SOURCE (spliced to this note's region) │ NOTE (edit + proposed folder + file) │ LINKS
// Redesign of the old 2-pane InboxPanel: the destination folder is no longer chosen from
// scratch — it's PROPOSED (find_home) and editable. Filing moves the note into its folder
// + the FSRS queue; acknowledging clears an in-place note. Data/actions reuse the inbox API.
export default function InboxReview({ onCount }) {
  const isAuthenticated = useStore(s => s.isAuthenticated);

  const [items,    setItems]    = useState([]);
  const [folders,  setFolders]  = useState([]);
  const [selected, setSelected] = useState(null);   // path
  const [draft,    setDraft]    = useState('');
  const [dest,     setDest]     = useState('');
  const [preview,  setPreview]  = useState(false);
  const [loading,  setLoading]  = useState(false);
  const [busy,     setBusy]     = useState(false);
  const [error,    setError]    = useState(null);
  const [status,   setStatus]   = useState('');

  const current = items.find(i => i.path === selected) || null;
  // review the LIVE edited content so source-splice + links reflect what you'll file
  const workingItem = current ? { ...current, content: draft } : null;

  const select = useCallback((item) => {
    setSelected(item.path);
    setDraft(item.content);
    setDest(item.suggestedFolder || '');
    setPreview(false);
    setStatus('');
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
    fetchChildren(null)
      .then(r => setFolders((r.folderPaths || []).filter(f => !/[/\\]_inbox$/.test(f))))
      .catch(() => {});
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isAuthenticated]);

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

  // Clicking a link jumps to that note if it's also awaiting review.
  const openStem = useCallback((stem) => {
    const s = stem.toLowerCase();
    const hit = items.find(i => (i.title || '').toLowerCase() === s
      || i.path.toLowerCase().endsWith(`/${s}.md`));
    if (hit) select(hit);
  }, [items, select]);

  return (
    <div className={styles.review}>
      <div className={styles.queue}>
        {loading && <p className={styles.status}>Loading…</p>}
        {error   && <p className={styles.statusError}>{error}</p>}
        {!loading && !error && items.length === 0 && (
          <p className={styles.status}>
            Inbox is empty. Capture a resource and its proposed notes appear here to review.
          </p>
        )}
        {items.map(it => (
          <button
            key={it.path}
            className={`${styles.row} ${selected === it.path ? styles.rowActive : ''}`}
            onClick={() => select(it)}
            title={it.title}
          >
            <span className={styles.rowTitle}>{it.title}</span>
            {it.inPlace
              ? <span className={styles.rowTag}>in place</span>
              : it.captureSeq != null && <span className={styles.rowTag}>#{it.captureSeq + 1}</span>}
          </button>
        ))}
      </div>

      {current ? (
        <div className={styles.panels}>
          <section className={styles.panelSource}>
            <SourceSplicePanel item={workingItem} />
          </section>

          <section className={styles.panelNote}>
            <div className={styles.noteHead}>
              {current.inPlace
                ? <span className={styles.srcLink}>✎ in place · {current.path.replace(/.*[/\\]/, '')}</span>
                : current.source && (
                  <a className={styles.srcLink} href={current.source} target="_blank" rel="noreferrer">
                    ↗ {current.source}
                  </a>)}
              <div className={styles.tabs}>
                <button className={`${styles.tab} ${!preview ? styles.tabOn : ''}`} onClick={() => setPreview(false)}>Edit</button>
                <button className={`${styles.tab} ${preview ? styles.tabOn : ''}`} onClick={() => setPreview(true)}>Preview</button>
              </div>
            </div>

            {preview
              ? <div className={styles.previewBox}><MarkdownContent content={draft} /></div>
              : <textarea className={styles.editor} value={draft}
                          onChange={e => setDraft(e.target.value)} spellCheck={false} />}

            <div className={styles.controls}>
              {current.inPlace ? (
                <div className={styles.actions}>
                  <button className={styles.primary} onClick={acknowledge} disabled={busy}>Save &amp; acknowledge</button>
                  {status && <span className={styles.actionStatus}>{status}</span>}
                </div>
              ) : (
                <>
                  <label className={styles.fieldLabel}>Proposed folder <span className={styles.hint}>(edit if wrong)</span></label>
                  <input
                    className={styles.folderInput}
                    list="review-folders"
                    value={dest}
                    onChange={e => setDest(e.target.value)}
                    placeholder="Choose or type a folder…"
                  />
                  <datalist id="review-folders">
                    {folders.map(f => <option key={f} value={f} />)}
                  </datalist>
                  <div className={styles.actions}>
                    <button className={styles.primary} onClick={file} disabled={busy}>Save &amp; file</button>
                    <button className={styles.ghost} onClick={discard} disabled={busy}>Discard</button>
                    {status && <span className={styles.actionStatus}>{status}</span>}
                  </div>
                </>
              )}
            </div>
          </section>

          <section className={styles.panelLinks}>
            <LinksPanel item={workingItem} onOpen={openStem} />
          </section>
        </div>
      ) : (
        <div className={styles.emptyMain}><p>Select a note to review.</p></div>
      )}
    </div>
  );
}
