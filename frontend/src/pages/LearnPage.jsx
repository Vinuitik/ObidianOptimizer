import { useEffect, useState } from 'react';
import useStore from '../store/useStore';
import LearnLayout from '../components/templates/LearnLayout';
import ResourcePanel from '../components/organisms/ResourcePanel';
import NotePanel from '../components/organisms/NotePanel';
import styles from './LearnPage.module.css';

export default function LearnPage() {
  const fetchRootChildren = useStore(s => s.fetchRootChildren);
  const fetchNoteNames    = useStore(s => s.fetchNoteNames);
  const isAuthenticated   = useStore(s => s.isAuthenticated);
  const vaultRoot         = useStore(s => s.vaultRoot);

  const [resourceType, setResourceType] = useState('pdf');

  // orientation: video is naturally wider, so horizontal split; everything else vertical
  const orientation = resourceType === 'video' ? 'horizontal' : 'vertical';

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
    </div>
  );
}
