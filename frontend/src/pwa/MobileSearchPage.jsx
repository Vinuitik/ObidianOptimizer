import { useState } from 'react';
import SearchBar from '../components/molecules/SearchBar';
import useStore from '../store/useStore';
import styles from './MobilePages.module.css';

// Search tab: reuses the shared SearchBar molecule (semantic search + results
// dropdown). Tapping a result opens it via the store's openTab.
export default function MobileSearchPage() {
  const isAuthenticated = useStore(s => s.isAuthenticated);
  const [query, setQuery] = useState('');

  if (!isAuthenticated) {
    return <div className={styles.gate}>Sign in to search your vault.</div>;
  }

  return (
    <div className={styles.page}>
      <h1 className={styles.pageTitle}>Search</h1>
      <SearchBar value={query} onChange={setQuery} />
      <p className={styles.hint}>Type to search across your vault. Results open in the Notes tab.</p>
    </div>
  );
}
