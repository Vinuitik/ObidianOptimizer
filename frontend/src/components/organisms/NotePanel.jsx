import { useState, useEffect, useCallback } from 'react';
import { fetchChildren, createNote } from '../../api/notes';
import useStore from '../../store/useStore';
import styles from './NotePanel.module.css';

function lastName(path) {
  if (!path) return '';
  return path.split(/[/\\]/).pop();
}

function parentOf(path) {
  const sep = path.includes('\\') ? '\\' : '/';
  const parts = path.split(sep);
  if (parts.length <= 1) return null;
  return parts.slice(0, -1).join(sep);
}

export default function NotePanel() {
  const vaultRoot      = useStore(s => s.vaultRoot);
  const isAuthenticated = useStore(s => s.isAuthenticated);

  const [currentPath, setCurrentPath] = useState(null);
  const [entries,     setEntries]     = useState({ folders: [], files: [] });
  const [loading,     setLoading]     = useState(false);
  const [error,       setError]       = useState(null);
  const [creating,    setCreating]    = useState(false);
  const [newName,     setNewName]     = useState('');
  const [listKey,     setListKey]     = useState(0); // bumped on navigate to replay animation

  const load = useCallback(async (path) => {
    setLoading(true);
    setError(null);
    try {
      const data = await fetchChildren(path);
      setCurrentPath(data.parentPath);
      setEntries({
        folders: data.folderPaths.map(p => ({ name: lastName(p), path: p })),
        files:   data.filePaths.map(p  => ({ name: lastName(p).replace(/\.md$/i, ''), path: p })),
      });
      setListKey(k => k + 1);
      setCreating(false);
    } catch {
      setError('Could not load folder.');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    if (isAuthenticated) load(null);
  }, [isAuthenticated, load]);

  const atRoot = !currentPath || currentPath === vaultRoot;
  const crumb  = currentPath ? lastName(currentPath) : '(root)';

  async function handleCreate() {
    const name = newName.trim();
    if (!name || !currentPath) return;
    try {
      await createNote(currentPath, name);
      setNewName('');
      setCreating(false);
      load(currentPath);
    } catch (e) {
      setError(e.message ?? 'Failed to create note.');
    }
  }

  return (
    <div className={styles.panel}>
      <div className={styles.header}>
        <button
          className={styles.upBtn}
          onClick={() => load(parentOf(currentPath))}
          disabled={atRoot || loading}
          title="Go to parent folder"
        >
          ↑
        </button>
        <span className={styles.crumb} title={currentPath ?? ''}>{crumb}</span>
        <button
          className={styles.newBtn}
          onClick={() => { setCreating(true); setNewName(''); setError(null); }}
          disabled={loading || !currentPath}
          title="New note in this folder"
        >
          +
        </button>
      </div>

      <div key={listKey} className={styles.list}>
        {creating && (
          <div className={styles.createRow}>
            <input
              className={styles.createInput}
              placeholder="Note name…"
              value={newName}
              autoFocus
              onChange={e => setNewName(e.target.value)}
              onKeyDown={e => {
                if (e.key === 'Enter') handleCreate();
                if (e.key === 'Escape') { setCreating(false); setError(null); }
              }}
            />
            <button className={styles.createConfirm} onClick={handleCreate} disabled={!newName.trim()}>✓</button>
            <button className={styles.createCancel}  onClick={() => { setCreating(false); setError(null); }}>✕</button>
          </div>
        )}

        {error   && <p className={styles.statusLine} style={{ color: 'var(--color-danger)' }}>{error}</p>}
        {loading && <p className={styles.statusLine}>Loading…</p>}

        {!loading && !error && entries.folders.length === 0 && entries.files.length === 0 && !creating && (
          <p className={styles.statusLine}>Empty folder</p>
        )}

        {entries.folders.map(f => (
          <button key={f.path} className={styles.folderRow} onClick={() => load(f.path)}>
            <span className={styles.icon}>📁</span>
            <span className={styles.rowName}>{f.name}</span>
            <span className={styles.chevron}>›</span>
          </button>
        ))}

        {entries.files.map(f => (
          <div key={f.path} className={styles.fileRow}>
            <span className={styles.icon}>📄</span>
            <span className={styles.rowName}>{f.name}</span>
          </div>
        ))}
      </div>
    </div>
  );
}
