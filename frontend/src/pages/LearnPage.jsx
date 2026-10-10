import { useEffect, useState } from 'react';
import useStore from '../store/useStore';
import InboxReview from '../components/organisms/InboxReview';
import FeedView from '../components/organisms/FeedView';
import styles from './LearnPage.module.css';

// Learn IS the ingest review now: capture a resource → it's segmented into proposed
// notes → you review + file them here. The old "Library" view (manual _workspace media
// shelf: ResourcePanel + NotePanel) predated the capture→ingest→inbox pipeline and was
// vestigial, so it's gone — resources flow through the Inbox instead of being browsed by
// hand. (ResourcePanel/NotePanel/LearnLayout kept in-tree but unused, easy to restore.)
export default function LearnPage() {
  const fetchRootChildren = useStore(s => s.fetchRootChildren);
  const fetchNoteNames    = useStore(s => s.fetchNoteNames);
  const isAuthenticated   = useStore(s => s.isAuthenticated);
  const vaultRoot         = useStore(s => s.vaultRoot);
  const fetchFeed         = useStore(s => s.fetchFeed);
  const pendingCount      = useStore(s => s.feedPending.length);
  const [mode, setMode]   = useState('inbox'); // 'feed' | 'inbox' — defaults to Inbox

  useEffect(() => { if (isAuthenticated) fetchFeed(); }, [isAuthenticated, fetchFeed]);

  useEffect(() => {
    if (isAuthenticated && !vaultRoot) {
      fetchRootChildren().then(() => fetchNoteNames());
    }
  }, [isAuthenticated]);

  if (!isAuthenticated) {
    return (
      <div className={styles.gate}>
        <p className={styles.gateText}>Sign in to use Learn.</p>
      </div>
    );
  }

  return (
    <div className={styles.page}>
      <div className={styles.viewTabs} role="tablist">
        <button
          role="tab" aria-selected={mode === 'feed'}
          className={`${styles.viewTab} ${mode === 'feed' ? styles.viewTabActive : ''}`}
          onClick={() => setMode('feed')}
        >
          Feed {pendingCount > 0 && <span className={styles.badge} data-testid="feed-badge">{pendingCount}</span>}
        </button>
        <button
          role="tab" aria-selected={mode === 'inbox'}
          className={`${styles.viewTab} ${mode === 'inbox' ? styles.viewTabActive : ''}`}
          onClick={() => setMode('inbox')}
        >Inbox</button>
      </div>
      <div className={styles.viewBody}>
        {mode === 'feed' ? <FeedView /> : <InboxReview />}
      </div>
    </div>
  );
}
