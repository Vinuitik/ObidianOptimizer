import { useEffect, useRef } from 'react';
import Icon from '../atoms/Icon';
import styles from './SearchBar.module.css';

export default function SearchBar({ value, onChange }) {
  const inputRef = useRef(null);

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

  return (
    <div className={styles.container}>
      <Icon name="search" size={15} color="var(--color-muted)" />
      <input
        ref={inputRef}
        className={styles.input}
        type="text"
        placeholder="Search vault"
        value={value}
        onChange={e => onChange(e.target.value)}
      />
      {value ? (
        <button className={styles.clear} onClick={() => onChange('')} title="Clear search">×</button>
      ) : (
        <kbd className={styles.kbd}>⌘K</kbd>
      )}
    </div>
  );
}
