import { useState, useEffect, useCallback } from 'react';
import Button from '../atoms/Button';
import IconButton from '../atoms/IconButton';
import Skeleton from '../atoms/Skeleton';
import styles from './FolderPicker.module.css';

/**
 * Generic folder-browser modal. It knows nothing about vault vs host — the
 * caller supplies a `loadPath` that returns a normalised view of a directory:
 *
 *   loadPath(path | null) => { current: string, parent: string | null,
 *                              dirs: [{ path, name }] }
 *
 * `parent === null` means "no further up" (vault root, or the drive list).
 *
 * Props:
 *   title        heading text
 *   loadPath     async loader (see above)
 *   initialPath  where to open (null = let the loader decide its top)
 *   onSelect     (path) => void   called with the chosen folder
 *   onClose      () => void
 *   note         optional ReactNode shown above the list (e.g. a warning)
 *   confirmLabel button label (default "Select this folder")
 */
export default function FolderPicker({
  title,
  loadPath,
  initialPath = null,
  onSelect,
  onClose,
  note = null,
  confirmLabel = 'Select this folder',
}) {
  const [view, setView]       = useState(null); // { current, parent, dirs }
  const [loading, setLoading] = useState(true);
  const [error, setError]     = useState(null);

  const go = useCallback(async (path) => {
    setLoading(true);
    setError(null);
    try {
      const v = await loadPath(path);
      setView(v);
    } catch {
      setError('Could not read that folder.');
    } finally {
      setLoading(false);
    }
  }, [loadPath]);

  useEffect(() => { go(initialPath); }, [go, initialPath]);

  return (
    <div className={styles.overlay} onClick={onClose}>
      <div className={styles.modal} onClick={e => e.stopPropagation()}>
        <div className={styles.head}>
          <span className={styles.title}>{title}</span>
          <IconButton icon="x" variant="ghost" onClick={onClose} aria-label="Close" />
        </div>

        {note && <div className={styles.note}>{note}</div>}

        <div className={styles.crumb}>
          <Button
            size="sm" variant="secondary"
            disabled={!view || view.parent == null || loading}
            onClick={() => go(view.parent)}
          >
            ↑ Up
          </Button>
          <span className={styles.current} title={view?.current ?? ''}>
            {view?.current || (loading ? 'Loading…' : '')}
          </span>
        </div>

        <div className={styles.list}>
          {loading && <Skeleton lines={4} height={32} />}
          {error && <div className={styles.error}>{error}</div>}
          {!loading && !error && view?.dirs.length === 0 && (
            <div className={styles.empty}>No sub-folders here.</div>
          )}
          {!loading && !error && view?.dirs.map(d => (
            <button key={d.path} className={styles.row} onClick={() => go(d.path)}>
              <span className={styles.folderIcon} aria-hidden>📁</span>
              <span className={styles.name}>{d.name}</span>
              <span className={styles.chev} aria-hidden>›</span>
            </button>
          ))}
        </div>

        <div className={styles.actions}>
          <Button variant="ghost" onClick={onClose}>Cancel</Button>
          <Button
            variant="primary"
            onClick={() => view?.current && onSelect(view.current)}
            disabled={!view?.current}
          >
            {confirmLabel}
          </Button>
        </div>
      </div>
    </div>
  );
}
