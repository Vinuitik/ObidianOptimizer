import { useState } from 'react';
import useStore from '../store/useStore';
import FeedView from '../components/organisms/FeedView';
import CapturePage from '../pwa/CapturePage';
import SegmentedTabs from '../components/molecules/SegmentedTabs';
import styles from './SourcesPage.module.css';

// Sources = the ONLY raw-data intake screen: Feed (swipe · bin · subscriptions) and Capture
// (link · idea/note · file). Everything accepted flows on into the Learn inbox.
const TABS = [{ id: 'feed', label: 'Feed' }, { id: 'capture', label: 'Capture' }];

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
        {view === 'feed' ? <FeedView /> : <CapturePage />}
      </div>
    </div>
  );
}
