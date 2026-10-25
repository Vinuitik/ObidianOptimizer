import { useEffect, useState } from 'react';
import useStore from '../store/useStore';
import InboxReview from '../components/organisms/InboxReview';
import TodayPlan from '../components/organisms/TodayPlan';
import WeekPlan from '../components/organisms/WeekPlan';
import { isInboxNotePath } from '../utils/sourceColor';
import SegmentedTabs from '../components/molecules/SegmentedTabs';
import styles from './LearnPage.module.css';

// Learn = Today (queue drawer + note review; scheduled track items below ONLY if not already units) | Week (7-day grid + track editor).
// Capture → ingest → proposed notes lands in the Inbox. Feed lives on the Sources page.
export default function LearnPage() {
  const fetchRootChildren = useStore(s => s.fetchRootChildren);
  const fetchNoteNames    = useStore(s => s.fetchNoteNames);
  const isAuthenticated   = useStore(s => s.isAuthenticated);
  const vaultRoot         = useStore(s => s.vaultRoot);
  const tracksEnabled     = useStore(s => s.settings.tracksEnabled ?? true);
  const todayItems        = useStore(s => s.todayItems) || [];
  const [view, setView]   = useState('today');

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

  if (!tracksEnabled) return <div className={styles.page}><div className={styles.viewBody}><InboxReview /></div></div>;

  // Inbox-note track items are the queue's units; only non-inbox items still need this list.
  const showPlan = todayItems.some(i => !isInboxNotePath(i.notePath));
  const tabs = [{ id: 'today', label: 'Today' }, { id: 'week', label: 'Week' }];

  return (
    <div className={styles.page}>
      <div className={styles.viewTabs}>
        <SegmentedTabs tabs={tabs} value={view} onChange={setView} aria-label="Learn sections" />
      </div>
      <div className={styles.viewBody}>
        {view === 'week' ? <WeekPlan /> : (
          <div className={styles.todayStack}>
            <InboxReview />
            {showPlan && <TodayPlan onCreateTrack={() => setView('week')} onManage={() => setView('week')} />}
          </div>
        )}
      </div>
    </div>
  );
}
