import { useEffect, useState } from 'react';
import useStore from '../store/useStore';
import LearnLayout from '../components/templates/LearnLayout';
import ResourcePanel from '../components/organisms/ResourcePanel';
import NotePanel from '../components/organisms/NotePanel';
import InboxPanel from '../components/organisms/InboxPanel';
import { fetchInbox } from '../api/inbox';
import styles from './LearnPage.module.css';

export default function LearnPage() {
  const fetchRootChildren = useStore(s => s.fetchRootChildren);
  const fetchNoteNames    = useStore(s => s.fetchNoteNames);
  const isAuthenticated   = useStore(s => s.isAuthenticated);
  const vaultRoot         = useStore(s => s.vaultRoot);

  const [view,         setView]         = useState('library'); // 'library' | 'inbox'
  const [resourceType, setResourceType] = useState('pdf');
  const [inboxCount,   setInboxCount]   = useState(0);

  // orientation: video is naturally wider, so horizontal split; everything else vertical
  const orientation = resourceType === 'video' ? 'horizontal' : 'vertical';

  useEffect(() => {
    if (isAuthenticated && !vaultRoot) {
      fetchRootChildren().then(() => fetchNoteNames());
    }
  }, [isAuthenticated]);

  // Keep the Inbox badge fresh without mounting the panel.
  useEffect(() => {
    if (!isAuthenticated) return;
    fetchInbox().then(l => setInboxCount(l.length)).catch(() => {});
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
      <div className={styles.viewTabs}>
        <button
          className={`${styles.viewTab} ${view === 'library' ? styles.viewTabActive : ''}`}
          onClick={() => setView('library')}
        >
          Library
        </button>
        <button
          className={`${styles.viewTab} ${view === 'inbox' ? styles.viewTabActive : ''}`}
          onClick={() => setView('inbox')}
        >
          Inbox{inboxCount > 0 && <span className={styles.badge}>{inboxCount}</span>}
        </button>
      </div>

      <div className={styles.viewBody}>
        {view === 'inbox' ? (
          <InboxPanel onCount={setInboxCount} />
        ) : (
          <LearnLayout
            orientation={orientation}
            slotA={
              <ResourcePanel
                resourceType={resourceType}
                onResourceTypeChange={setResourceType}
              />
            }
            slotB={<NotePanel />}
          />
        )}
      </div>
    </div>
  );
}
