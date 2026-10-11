import { useState, useEffect, useRef } from 'react';
import useStore from '../../store/useStore';
import { captureResource, captureFile } from '../../api/capture';
import { searchNotes } from '../../api/notes';
import styles from './TrackAddBox.module.css';

const URL_RE = /^https?:\/\/\S+$/i;

// Pure: decide which add-path a box's text takes.
//   { kind: 'empty' } | { kind: 'urls', urls } | { kind: 'text', text }
// Every non-empty line must be an http(s) URL for the 'urls' path; a mixed paste is text.
export function classifyInput(raw) {
  const lines = String(raw ?? '').split('\n').map(l => l.trim()).filter(Boolean);
  if (lines.length === 0) return { kind: 'empty' };
  if (lines.every(l => URL_RE.test(l))) return { kind: 'urls', urls: [...new Set(lines)] };
  return { kind: 'text', text: lines.join(' ') };
}

// Item chip type. link/file can't be told apart from note once ingested (no backend
// field), so persisted items are only: note (has notePath) or plain (no notePath).
// link/file/processing chips are shown only for this session's in-flight captures.
export function itemKind(item) {
  return item.notePath ? 'note' : 'plain';
}

export function ItemTypeChip({ kind }) {
  return <span className={`${styles.chip} ${styles['chip_' + kind]}`}>{kind === 'processing' ? 'processing…' : kind}</span>;
}

const POLL_MS = 5000;
const POLL_MAX = 6;

export default function TrackAddBox({ trackId }) {
  const items = useStore(s => s.trackItems[trackId]) ?? [];
  const fetchTrackItems = useStore(s => s.fetchTrackItems);
  const addTrackItem = useStore(s => s.addTrackItem);
  const showToast = useStore(s => s.showToast);

  const [text, setText] = useState('');
  const [matches, setMatches] = useState([]);
  const [busy, setBusy] = useState(false);
  const [results, setResults] = useState([]);   // [{label, status, message}]
  const [pending, setPending] = useState([]);   // [{label, kind: 'link'|'file'}] awaiting ingest
  const [dragOver, setDragOver] = useState(false);
  const fileRef = useRef(null);
  const baseCount = useRef(0);
  const polls = useRef(0);

  const cls = classifyInput(text);

  // Debounced note search for the plain-text path.
  useEffect(() => {
    if (cls.kind !== 'text') { setMatches([]); return undefined; }
    const ctrl = new AbortController();
    const t = setTimeout(() => {
      searchNotes(cls.text, { signal: ctrl.signal })
        .then(r => setMatches(Array.isArray(r) ? r.slice(0, 6) : []))
        .catch(() => { /* aborted / offline: dropdown just stays empty */ });
    }, 250);
    return () => { clearTimeout(t); ctrl.abort(); };
  }, [cls.kind, cls.text]);

  // Captured items land async: refetch a few times; each new item retires one placeholder.
  useEffect(() => {
    if (pending.length === 0) return undefined;
    const t = setTimeout(async () => {
      polls.current += 1;
      const fresh = await fetchTrackItems(trackId).catch(() => null);
      const grown = fresh ? fresh.length - baseCount.current : 0;
      if (grown > 0) {
        baseCount.current = fresh.length;
        setPending(p => p.slice(grown));
      } else if (polls.current >= POLL_MAX) {
        setPending([]);
      } else {
        setPending(p => [...p]); // re-arm the timer
      }
    }, POLL_MS);
    return () => clearTimeout(t);
  }, [pending, trackId, fetchTrackItems]);

  function startPending(entries) {
    if (entries.length === 0) return;
    baseCount.current = items.length;
    polls.current = 0;
    setPending(p => [...p, ...entries]);
  }

  async function submitUrls(urls) {
    setBusy(true);
    setResults(urls.map(u => ({ label: u, status: 'pending' })));
    const ok = [];
    for (const url of urls) {
      const res = await captureResource(url, trackId);
      if (res.ok && !res.duplicate) ok.push({ label: url, kind: 'link' });
      setResults(rs => rs.map(r => r.label === url
        ? { label: url, status: res.ok ? (res.duplicate ? 'duplicate' : 'ok') : 'error', message: res.error }
        : r));
    }
    setBusy(false);
    if (ok.length) { setText(''); startPending(ok); fetchTrackItems(trackId); }
  }

  async function submitFiles(files) {
    setBusy(true);
    const ok = [];
    for (const file of files) {
      setResults(rs => [...rs, { label: file.name, status: 'pending' }]);
      const res = await captureFile(file, trackId);
      if (res.ok && !res.duplicate) ok.push({ label: file.name, kind: 'file' });
      setResults(rs => rs.map(r => r.label === file.name
        ? { label: file.name, status: res.ok ? (res.duplicate ? 'duplicate' : 'ok') : 'error', message: res.error }
        : r));
    }
    setBusy(false);
    startPending(ok);
  }

  async function addItem(title, notePath) {
    try {
      await addTrackItem(trackId, title, notePath ?? null);
      setText('');
      setMatches([]);
    } catch (e) {
      showToast(`Couldn't add item: ${e.message ?? e}`);
    }
  }

  function onSubmit(e) {
    e.preventDefault();
    if (cls.kind === 'urls') submitUrls(cls.urls);
    else if (cls.kind === 'text') addItem(cls.text, null);
  }

  function onDrop(e) {
    e.preventDefault();
    setDragOver(false);
    const files = [...(e.dataTransfer?.files ?? [])];
    if (files.length) submitFiles(files);
  }

  return (
    <div
      className={`${styles.box} ${dragOver ? styles.dragOver : ''}`}
      onDragOver={e => { if (e.dataTransfer?.types?.includes('Files')) { e.preventDefault(); setDragOver(true); } }}
      onDragLeave={() => setDragOver(false)}
      onDrop={onDrop}
      data-testid="track-add-box"
    >
      <form onSubmit={onSubmit}>
        <textarea
          className={styles.input}
          rows={text.includes('\n') ? 4 : 1}
          placeholder="Paste a link, search a note, or type an item — or drop a file"
          value={text}
          onChange={e => setText(e.target.value)}
          onKeyDown={e => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); onSubmit(e); } }}
        />
        <div className={styles.actions}>
          <button type="button" className={styles.secondary} onClick={() => fileRef.current?.click()}>Attach file</button>
          <input
            ref={fileRef}
            type="file"
            multiple
            hidden
            data-testid="track-add-file"
            onChange={e => { const f = [...e.target.files]; e.target.value = ''; if (f.length) submitFiles(f); }}
          />
          <button type="submit" className={styles.primary} disabled={busy || cls.kind === 'empty'}>
            {cls.kind === 'urls' ? `Capture ${cls.urls.length} link${cls.urls.length > 1 ? 's' : ''}` : 'Add'}
          </button>
        </div>
      </form>

      {cls.kind === 'text' && (
        <ul className={styles.dropdown}>
          {matches.map(m => {
            const path = m.path ?? m.note_path ?? m.notePath;
            const title = m.title ?? String(path ?? '').split('/').pop().replace(/\.md$/, '');
            return (
              <li key={path}>
                <button type="button" className={styles.option} onClick={() => addItem(title, path)}>
                  <ItemTypeChip kind="note" /> <span className={styles.optTitle}>{title}</span>
                  <span className={styles.optPath}>{path}</span>
                </button>
              </li>
            );
          })}
          <li>
            <button type="button" className={styles.option} onClick={() => addItem(cls.text, null)}>
              <ItemTypeChip kind="plain" /> <span className={styles.optTitle}>Add as plain item “{cls.text}”</span>
            </button>
          </li>
        </ul>
      )}

      {results.length > 0 && (
        <ul className={styles.results}>
          {results.map((r, i) => (
            <li key={`${r.label}-${i}`} className={styles.resultRow}>
              <span>{r.status === 'pending' ? '…' : r.status === 'ok' ? '✓' : r.status === 'duplicate' ? '⚠' : '✗'}</span>
              <span className={styles.resultLabel} title={r.label}>{r.label}</span>
              {r.status === 'duplicate' && <span className={styles.msg}>already captured</span>}
              {r.status === 'error' && <span className={styles.msg}>{r.message}</span>}
            </li>
          ))}
        </ul>
      )}

      {pending.length > 0 && (
        <ul className={styles.results}>
          {pending.map((p, i) => (
            <li key={`${p.label}-${i}`} className={styles.resultRow}>
              <ItemTypeChip kind={p.kind} /> <ItemTypeChip kind="processing" />
              <span className={styles.resultLabel} title={p.label}>{p.label}</span>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
