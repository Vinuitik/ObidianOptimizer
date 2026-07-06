import { Outlet } from 'react-router-dom';
import { useEffect } from 'react';
import useStore from '../store/useStore';
import useOffline from './useOffline';
import { flushOutbox, setDriveMode } from './offlineApi';
import { hasCreds } from './setup';
import LoginModal from '../components/organisms/LoginModal';
import BottomNav from './BottomNav';
import styles from './MobileLayout.module.css';

// The installed-PWA shell: an offline banner, the routed page, and the bottom nav.
// The pages it hosts (ReviewPage, LearnPage) are the SAME components the desktop
// renders — only this shell differs. LoginModal is included so a 401 in the PWA can
// prompt sign-in (captures/grades queue until then).
export default function MobileLayout() {
  const checkAuth       = useStore(s => s.checkAuth);
  const isAuthenticated = useStore(s => s.isAuthenticated);
  const showLogin       = useStore(s => s.showLogin);
  const online          = useOffline();

  // Same bootstrap the desktop App does — auth gate + revalidate on focus.
  useEffect(() => {
    checkAuth();
    const onFocus = () => checkAuth();
    window.addEventListener('focus', onFocus);
    return () => window.removeEventListener('focus', onFocus);
  }, [checkAuth]);

  // If this device is linked to Drive, the PWA reads its review set from the Drive-pulled
  // IndexedDB (not the server). Unlinked → falls back to the server path (still works online).
  useEffect(() => { hasCreds().then(setDriveMode).catch(() => {}); }, []);

  // Reconnect → replay anything captured while offline (grade outbox is empty until
  // the offline-review seam lands; flush is a no-op when there's nothing queued).
  useEffect(() => {
    if (online) flushOutbox().catch(() => {});
  }, [online]);

  return (
    <div className={styles.shell}>
      {!online && (
        <div className={styles.offlineBar}>Offline — showing your downloaded set</div>
      )}
      <main className={styles.content}>
        <Outlet context={{ isAuthenticated }} />
      </main>
      <BottomNav />
      {showLogin && <LoginModal />}
    </div>
  );
}
