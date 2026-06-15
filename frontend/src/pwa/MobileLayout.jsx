import { Outlet } from 'react-router-dom';
import { useEffect } from 'react';
import useStore from '../store/useStore';
import useOffline from './useOffline';
import BottomNav from './BottomNav';
import styles from './MobileLayout.module.css';

// Single-column shell: scrollable content + a fixed bottom tab bar. The leaf
// pages it hosts (ReviewPage, etc.) are the SAME components the desktop renders —
// only the surrounding shell differs (the whole point of the responsive design).
export default function MobileLayout() {
  const checkAuth = useStore(s => s.checkAuth);
  const isAuthenticated = useStore(s => s.isAuthenticated);
  const online = useOffline();

  // Same bootstrap the desktop App does — auth gate + initial vault load.
  useEffect(() => { checkAuth(); }, [checkAuth]);

  return (
    <div className={styles.shell}>
      {!online && (
        <div className={styles.offlineBar}>Offline — showing your downloaded review set</div>
      )}
      <main className={styles.content}>
        <Outlet context={{ isAuthenticated }} />
      </main>
      <BottomNav />
    </div>
  );
}
