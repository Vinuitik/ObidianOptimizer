import { useEffect } from 'react';
import useStore from '../store/useStore';
import InboxReview from '../components/organisms/InboxReview';
import FeedView from '../components/organisms/FeedView';
import Skeleton from '../components/atoms/Skeleton';
import SegmentedTabs from '../components/molecules/SegmentedTabs';
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
  const feedLoaded        = useStore(s => s.feedLoaded);
  const mode              = useStore(s => s.learnView);     // 'feed' | 'inbox' | null (undecided)
  const setMode           = useStore(s => s.setLearnView);

  useEffect(() => { if (isAuthenticated) fetchFeed(); }, [isAuthenticated, fetchFeed]);

  // Open on Feed when there is something to triage, else Inbox. Decided ONCE per visit (after the
  // first feed fetch resolves) so swiping the last card doesn't yank the user to Inbox.
  useEffect(() => {
    if (isAuthenticated && feedLoaded && mode == null) setMode(pendingCount > 0 ? 'feed' : 'inbox');
  }, [isAuthenticated, feedLoaded, mode, pendingCount, setMode]);
  useEffect(() => () => setMode(null), [setMode]);   // next visit re-decides

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

  const tabs = [
    { id: 'feed', label: 'Feed', badge: pendingCount > 0 ? pendingCount : undefined },
    { id: 'inbox', label: 'Inbox' },
  ];

  return (
    <div className={styles.page}>
      <div className={styles.viewTabs}>
        <SegmentedTabs tabs={tabs} value={mode ?? 'inbox'} onChange={setMode} aria-label="Learn sections" />
      </div>
      <div className={styles.viewBody}>
        {mode == null ? <div className={styles.loading}><Skeleton lines={4} height={44} /></div>
          : mode === 'feed' ? <FeedView /> : <InboxReview />}
      </div>
    </div>
  );
}
