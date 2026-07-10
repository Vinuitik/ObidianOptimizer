import { useEffect, useState } from 'react';
import useStore from '../../store/useStore';
import { fetchSyncProgress } from '../../api/sync';
import styles from './SyncBanner.module.css';

// Global "still syncing" banner for the full site. While a Drive pull or DB restore is
// running the app stays usable — notes appear as their files land — and this bar tells
// the user the vault isn't 100% here yet, so "content missing" reads as "not synced yet"
// rather than a bug. Polls the CHEAP /sync/progress endpoint (no Drive quota call), fast
// while active and a slow heartbeat when idle. Renders nothing when there's no sync.
export default function SyncBanner() {
  const isAuthenticated = useStore(s => s.isAuthenticated);
  const [p, setP] = useState(null);

  useEffect(() => {
    if (!isAuthenticated) { setP(null); return; }
    let alive = true;
    let timer;
    const tick = async () => {
      let active = false;
      try {
        const next = await fetchSyncProgress();
        if (!alive) return;
        setP(next);
        active = Boolean(next.downloading || next.dbRestoring);
      } catch {
        if (!alive) return;
      }
      timer = setTimeout(tick, active ? 3000 : 15000);
    };
    tick();
    return () => { alive = false; clearTimeout(timer); };
  }, [isAuthenticated]);

  if (!p) return null;

  if (p.dbRestoring) {
    const phase = p.dbRestorePhase ? ` — ${p.dbRestorePhase}` : '';
    return (
      <div className={styles.bar} role="status">
        <span className={styles.spinner} aria-hidden="true" />
        <span>Restoring from Drive{phase}. Some notes won’t appear until it finishes.</span>
      </div>
    );
  }

  if (p.downloading) {
    const total = p.downloadTotal || 0;
    const done = p.downloadDone || 0;
    const failed = p.downloadFailed || 0;
    const failNote = failed > 0 ? `, ${failed} to retry` : '';
    return (
      <div className={styles.bar} role="status">
        <span className={styles.spinner} aria-hidden="true" />
        <span>
          Syncing from Drive — {done}/{total}{failNote}. Some content may not be available yet.
        </span>
      </div>
    );
  }

  return null;
}
