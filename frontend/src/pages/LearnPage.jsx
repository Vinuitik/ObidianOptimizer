import { useEffect } from 'react';
import useStore from '../store/useStore';
import InboxReview from '../components/organisms/InboxReview';
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
      <div className={styles.viewBody}>
        <InboxReview />
      </div>
    </div>
  );
}
