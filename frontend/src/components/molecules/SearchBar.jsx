import { useEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import useStore from '../../store/useStore';
import { useSearch } from '../../utils/useSearch';
import Icon from '../atoms/Icon';
import styles from './SearchBar.module.css';

export default function SearchBar({ value, onChange }) {
  const inputRef    = useRef(null);
  const containerRef = useRef(null);
  const openTab     = useStore(s => s.openTab);

  const [open,     setOpen]     = useState(false);
  const [dropRect, setDropRect] = useState(null);

  // Search follows the typed value, focused or not — results survive a
  // click-away. Dismissal is explicit: Escape, ×, picking a result, or
  // emptying the query.
  const { results, loading } = useSearch(value);

  useEffect(() => {
    setOpen(value.trim().length >= 2);
  }, [value]);

  // Keep the dropdown anchored under the bar (portal is position:fixed)
  useEffect(() => {
    function anchor() {
      if (containerRef.current) {
        const r = containerRef.current.getBoundingClientRect();
        setDropRect({ top: r.bottom + 4, left: r.left, width: r.width });
      }
    }
    if (open) {
      anchor();
      window.addEventListener('resize', anchor);
      return () => window.removeEventListener('resize', anchor);
    }
  }, [open, value]);

  // ⌘K / Ctrl+K global focus shortcut
  useEffect(() => {
    function handleKey(e) {
      if ((e.metaKey || e.ctrlKey) && e.key === 'k') {
        e.preventDefault();
        inputRef.current?.focus();
      }
    }
    window.addEventListener('keydown', handleKey);
    return () => window.removeEventListener('keydown', handleKey);
  }, []);

  function pick(notePath) {
    openTab(notePath);
    setOpen(false);
  }

  const showDrop = open && dropRect;

  return (
    <div ref={containerRef} className={styles.container}>
      <Icon name="search" size={15} color="var(--color-muted)" />
      <input
        ref={inputRef}
        className={styles.input}
        type="text"
        placeholder="Search vault"
        value={value}
        onChange={e => onChange(e.target.value)}
        onFocus={() => { if (value.trim().length >= 2) setOpen(true); }}
        onKeyDown={e => {
          if (e.key === 'Escape') setOpen(false);
          if (e.key === 'Enter' && results.length > 0) pick(results[0].notePath);
        }}
      />
      {value ? (
        <button
          className={styles.clear}
          onClick={() => { onChange(''); setOpen(false); }}
          title="Clear"
        >×</button>
      ) : (
        <kbd className={styles.kbd}>⌘K</kbd>
      )}

      {showDrop && createPortal(
        <div
          className={styles.dropdown}
          style={{ top: dropRect.top, left: dropRect.left, width: dropRect.width }}
        >
          {loading && results.length === 0 && (
            <div className={styles.dropLoading}>Searching…</div>
          )}
          {!loading && results.length === 0 && (
            <div className={styles.dropLoading}>No matches</div>
          )}
          {results.map(r => {
            const title = r.notePath.split(/[/\\]/).pop().replace(/\.md$/, '');
            return (
              <button
                key={r.notePath}
                className={styles.dropItem}
                onMouseDown={e => {
                  e.preventDefault();
                  pick(r.notePath);
                }}
              >
                <span className={styles.dropTitle}>{title}</span>
                <span className={styles.dropSnippet}>{r.snippet}</span>
              </button>
            );
          })}
        </div>,
        document.body
      )}
    </div>
  );
}
