import { useState, useEffect } from 'react';
import useStore from '../../store/useStore';
import { searchNotes } from '../../api/notes';
import Input from '../atoms/Input';
import Badge from '../atoms/Badge';
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
  const tone = { link: 'accent', note: 'success', file: 'ember', processing: 'accent' }[kind] ?? 'neutral';
  return <Badge tone={tone}>{kind === 'processing' ? 'processing…' : kind}</Badge>;
}

// Track items must reference an existing note (backend 400s without notePath), so the add box is
// a note picker only: debounced search → click a hit → addTrackItem(trackId, title, notePath).
// URL/file intake lives on the Sources page, not here.
export default function TrackAddBox({ trackId }) {
  const addTrackItem = useStore(s => s.addTrackItem);
  const showToast = useStore(s => s.showToast);
  const [query, setQuery] = useState('');
  const [matches, setMatches] = useState([]);

  const q = query.trim();

  useEffect(() => {
    if (!q) { setMatches([]); return undefined; }
    const ctrl = new AbortController();
    const t = setTimeout(() => {
      searchNotes(q, { signal: ctrl.signal })
        .then(r => setMatches(Array.isArray(r) ? r.slice(0, 8) : []))
        .catch(() => { /* aborted / offline: dropdown just stays empty */ });
    }, 250);
    return () => { clearTimeout(t); ctrl.abort(); };
  }, [q]);

  async function pick(title, notePath) {
    try {
      await addTrackItem(trackId, title, notePath);
      setQuery('');
      setMatches([]);
    } catch (e) {
      showToast(`Couldn't add item: ${e.message ?? e}`);
    }
  }

  return (
    <div className={styles.box} data-testid="track-add-box">
      <Input
        aria-label="Add a note to this track"
        placeholder="Search a note to add…"
        value={query}
        onChange={e => setQuery(e.target.value)}
      />
      {q && (
        <ul className={styles.dropdown}>
          {matches.map(m => {
            const path = m.path ?? m.note_path ?? m.notePath;
            const title = m.title ?? String(path ?? '').split('/').pop().replace(/\.md$/, '');
            return (
              <li key={path}>
                <button type="button" className={styles.option} onClick={() => pick(title, path)}>
                  <ItemTypeChip kind="note" /> <span className={styles.optTitle}>{title}</span>
                  <span className={styles.optPath}>{path}</span>
                </button>
              </li>
            );
          })}
          {matches.length === 0 && <li className={styles.msg}>No matching notes</li>}
        </ul>
      )}
    </div>
  );
}
