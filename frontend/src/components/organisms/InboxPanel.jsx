import { useState, useEffect, useCallback } from 'react';
import { fetchInbox, fileInboxNote, discardInboxNote, acknowledgeCapture } from '../../api/inbox';
import { fetchChildren, updateNote } from '../../api/notes';
import useStore from '../../store/useStore';
import styles from './InboxPanel.module.css';

// Triage queue for everything the ingest agent touched. Two shapes share it:
//   inPlace=false → a new note staged in _inbox/; edit it, pick a folder, file it.
//   inPlace=true  → an existing note rewritten below an embed; edit it, acknowledge.
// Filing moves a note into the FSRS review queue; acknowledging just clears the
// in-place note (already live) and trashes its pre-rewrite snapshot. onCount badges.
export default function InboxPanel({ onCount }) {
  const isAuthenticated = useStore(s => s.isAuthenticated);

  const [items,    setItems]    = useState([]);
  const [folders,  setFolders]  = useState([]);
  const [selected, setSelected] = useState(null);   // path
  const [draft,    setDraft]    = useState('');      // edited content
  const [dest,     setDest]      = useState('');     // target folder (absolute)
  const [loading,  setLoading]  = useState(false);
  const [busy,     setBusy]     = useState(false);
  const [error,    setError]    = useState(null);
  const [status,   setStatus]   = useState('');

  const current = items.find(i => i.path === selected) || null;

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
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selected]);

  useEffect(() => {
    if (!isAuthenticated) return;
    load();
    fetchChildren(null)
      .then(r => setFolders((r.folderPaths || []).filter(f => !/[/\\]_inbox$/.test(f))))
      .catch(() => {});
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isAuthenticated]);

  function select(item) {
    setSelected(item.path);
    setDraft(item.content);
    setDest(item.suggestedFolder || '');
    setStatus('');
  }

  async function file() {
    if (!current) return;
    if (!dest.trim()) { setStatus('Pick a destination folder.'); return; }
    setBusy(true);
    setStatus('Filing…');
    try {
      await fileInboxNote(current.path, dest.trim(), draft);
      setStatus('');
      load();
    } catch (e) {
      setStatus(`Failed: ${e.message || e}`);
    } finally {
      setBusy(false);
    }
  }

  async function acknowledge() {
    if (!current) return;
    setBusy(true);
    setStatus('Saving…');
    try {
      // The note is already live at its real path — persist edits, then clear it
      // from the queue (which also trashes the pre-rewrite source snapshot).
      if (draft !== current.content) await updateNote(current.path, draft);
      await acknowledgeCapture(current.captureId);
      setStatus('');
      load();
    } catch (e) {
      setStatus(`Failed: ${e.message || e}`);
    } finally {
      setBusy(false);
    }
  }

  async function discard() {
    if (!current) return;
    setBusy(true);
    try {
      await discardInboxNote(current.path);
      load();
    } catch (e) {
      setStatus(`Failed: ${e.message || e}`);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className={styles.panel}>
      <div className={styles.body}>
        {/* Left: queue */}
        <div className={styles.list}>
          {loading && <p className={styles.status}>Loading…</p>}
          {error   && <p className={styles.statusError}>{error}</p>}
          {!loading && !error && items.length === 0 && (
            <p className={styles.status}>
              Inbox is empty. Capture something with the browser extension and the
              generated notes will appear here to review and file.
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
                ? <span className={styles.rowSource}>in place</span>
                : it.source && <span className={styles.rowSource}>{hostOf(it.source)}</span>}
            </button>
          ))}
        </div>

        {/* Right: editor + file controls */}
        <div className={styles.editor}>
          {current ? (
            <>
              {current.inPlace ? (
                <span className={styles.sourceLink} title={current.path}>
                  ✎ Rewritten in place · {current.path.replace(/.*[/\\]/, '')}
                </span>
              ) : current.source && (
                <a className={styles.sourceLink} href={current.source} target="_blank" rel="noreferrer">
                  ↗ {current.source}
                </a>
              )}
              <textarea
                className={styles.textarea}
                value={draft}
                onChange={e => setDraft(e.target.value)}
                spellCheck={false}
              />
              <div className={styles.controls}>
                {current.inPlace ? (
                  // Already lives in its real folder — no destination to pick.
                  <div className={styles.actions}>
                    <button className={styles.primary} onClick={acknowledge} disabled={busy}>
                      Save &amp; acknowledge
                    </button>
                    {status && <span className={styles.actionStatus}>{status}</span>}
                  </div>
                ) : (
                  <>
                    <label className={styles.fieldLabel}>File into folder</label>
                    <input
                      className={styles.folderInput}
                      list="inbox-folders"
                      value={dest}
                      onChange={e => setDest(e.target.value)}
                      placeholder="Choose or type a folder…"
                    />
                    <datalist id="inbox-folders">
                      {folders.map(f => <option key={f} value={f} />)}
                    </datalist>

                    <div className={styles.actions}>
                      <button className={styles.primary} onClick={file} disabled={busy}>
                        Save &amp; file
                      </button>
                      <button className={styles.ghost} onClick={discard} disabled={busy}>
                        Discard
                      </button>
                      {status && <span className={styles.actionStatus}>{status}</span>}
                    </div>
                  </>
                )}
              </div>
            </>
          ) : (
            <div className={styles.empty}>
              <p className={styles.emptyText}>Select a note to review and file.</p>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}

function hostOf(url) {
  try { return new URL(url).host; } catch { return url; }
}
