import { useState } from 'react';
import useStore from '../store/useStore';
import FeedView from '../components/organisms/FeedView';
import SegmentedTabs from '../components/molecules/SegmentedTabs';
import styles from './SourcesPage.module.css';

// Sources = Feed (swipe) · Subscriptions, ONE tab row. Manual capture is the global Capture
// button. Everything accepted flows on into the Learn inbox.
const TABS = [{ id: 'feed', label: 'Feed' }, { id: 'subscriptions', label: 'Subscriptions' }];

export default function SourcesPage() {
  const isAuthenticated = useStore(s => s.isAuthenticated);
  const [view, setView] = useState('feed');

  if (!isAuthenticated) {
    return (
      <div className={styles.gate}>
        <p className={styles.gateText}>Sign in to use Sources.</p>
      </div>
    );
  }

  return (
    <div className={styles.page}>
      <div className={styles.viewTabs}>
        <SegmentedTabs tabs={TABS} value={view} onChange={setView} aria-label="Sources sections" />
      </div>
      <div className={styles.viewBody}>
        <FeedView view={view} onView={setView} />
      </div>
    </div>
  );
}
