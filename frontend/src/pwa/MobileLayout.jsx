import { Outlet } from 'react-router-dom';
import { useEffect } from 'react';
import useStore from '../store/useStore';
import useOffline from './useOffline';
import { flushOutbox, setDriveMode } from './offlineApi';
import { pushMailbox } from './mailbox';
import { hasCreds } from './setup';
import { maybeAutoSync } from './autoSync';
import LoginModal from '../components/organisms/LoginModal';
import RouteErrorBoundary from '../components/organisms/RouteErrorBoundary';
import BottomNav from './BottomNav';
import styles from './MobileLayout.module.css';

// The installed-PWA shell: an offline banner, the routed page, and the bottom nav.
// The pages it hosts (ReviewPage, LearnPage) are the SAME components the desktop
// renders — only this shell differs. LoginModal is included so a 401 in the PWA can
// prompt sign-in (captures/grades queue until then).
export default function MobileLayout() {
  const checkAuth       = useStore(s => s.checkAuth);
  const setShowLogin    = useStore(s => s.setShowLogin);
  const isAuthenticated = useStore(s => s.isAuthenticated);
  const showLogin       = useStore(s => s.showLogin);
  const online          = useOffline();

  // Same bootstrap the desktop App does — auth gate + revalidate on focus — but the
  // installed PWA has no visible "Sign in" affordance, so if the check comes back
  // unauthenticated we AUTO-OPEN the login modal (rather than waiting for a write to
  // 401). Skip while offline: /login is unreachable and the downloaded set still works.
  useEffect(() => {
    let alive = true;
    const gate = async () => {
      try { await checkAuth(); } catch { /* offline / server down */ }
      if (alive && navigator.onLine && !useStore.getState().isAuthenticated) {
        setShowLogin(true);
      }
    };
    gate();
    const onFocus = () => gate();
    window.addEventListener('focus', onFocus);
    return () => { alive = false; window.removeEventListener('focus', onFocus); };
  }, [checkAuth, setShowLogin]);

  // If this device is linked to Drive, the PWA reads its review set from the Drive-pulled
  // IndexedDB (not the server). Unlinked → falls back to the server path (still works online).
  useEffect(() => { hasCreds().then(setDriveMode).catch(() => {}); }, []);

  // Reconnect → sync the outbox. Drive-linked: grades go to the Drive mailbox (works with
  // the laptop off); anything left (e.g. captures) still tries the server. Unlinked: just
  // the server-direct flush. All no-ops when the outbox is empty.
  useEffect(() => {
    if (!online) return;
    (async () => {
      if (await hasCreds().catch(() => false)) await pushMailbox().catch(() => {});
      await flushOutbox().catch(() => {});
    })();
  }, [online]);

  // Auto-sync (cron-like): keep the Drive-linked review set fresh without a manual tap.
  // Fires on launch (mount) and reconnect (online flips true), on tab-focus, and every
  // 30 min while open. maybeAutoSync self-gates on staleness (6h) so these are cheap.
  useEffect(() => {
    if (!online) return;
    maybeAutoSync();
    const onVis = () => { if (document.visibilityState === 'visible') maybeAutoSync(); };
    document.addEventListener('visibilitychange', onVis);
    const id = setInterval(() => maybeAutoSync(), 30 * 60 * 1000);
    return () => { document.removeEventListener('visibilitychange', onVis); clearInterval(id); };
  }, [online]);

  return (
    <div className={styles.shell}>
      {!online && (
        <div className={styles.offlineBar}>Offline — showing your downloaded set</div>
      )}
      <main className={styles.content}>
        <RouteErrorBoundary>
          <Outlet context={{ isAuthenticated }} />
        </RouteErrorBoundary>
      </main>
      <BottomNav />
      {showLogin && <LoginModal />}
    </div>
  );
}
