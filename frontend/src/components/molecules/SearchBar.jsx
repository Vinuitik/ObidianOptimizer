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

  const [focused,  setFocused]  = useState(false);
  const [dropRect, setDropRect] = useState(null);

  // Only run semantic search when the bar is focused and query is long enough
  const { results, loading } = useSearch(focused ? value : null);

  // Recompute dropdown anchor when the bar is focused or query changes
  useEffect(() => {
    if (focused && containerRef.current) {
      const r = containerRef.current.getBoundingClientRect();
      setDropRect({ top: r.bottom + 4, left: r.left, width: r.width });
    }
  }, [focused, value]);

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

  const showDrop = focused && (loading || results.length > 0);

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
        onFocus={() => setFocused(true)}
        // small delay so a mousedown on a result fires before focus is lost
        onBlur={() => setTimeout(() => setFocused(false), 150)}
      />
      {value ? (
        <button className={styles.clear} onClick={() => onChange('')} title="Clear">×</button>
      ) : (
        <kbd className={styles.kbd}>⌘K</kbd>
      )}

      {showDrop && dropRect && createPortal(
        <div
          className={styles.dropdown}
          style={{ top: dropRect.top, left: dropRect.left, width: dropRect.width }}
        >
          {loading && results.length === 0 && (
            <div className={styles.dropLoading}>Searching…</div>
          )}
          {results.map(r => {
            const title = r.notePath.split(/[/\\]/).pop().replace(/\.md$/, '');
            return (
              <button
                key={r.notePath}
                className={styles.dropItem}
                onMouseDown={e => {
                  e.preventDefault();
                  openTab(r.notePath);
                  setFocused(false);
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
