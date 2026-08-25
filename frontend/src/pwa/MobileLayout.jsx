import { Outlet } from 'react-router-dom';
import { useEffect } from 'react';
import useStore from '../store/useStore';
import useOffline from './useOffline';
import { flushOutbox, setDriveMode, OUTBOX_RETRY_MS } from './offlineApi';
import { pushMailbox } from './mailbox';
import { hasCreds } from './setup';
import { maybeAutoSync } from './autoSync';
import { armQuitNotify, showLocalNotification } from './quitNotify';
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
  const showToast       = useStore(s => s.showToast);
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
        console.warn('[AUTH] MobileLayout gate: online && !authenticated → opening login modal');
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
  //
  // Also retried on visibilitychange, not just the `online` flip: Android/Chrome freezes
  // a backgrounded tab's JS, so a phone that reconnects while locked/backgrounded never
  // dispatches 'online' to it — the outbox effect above never reruns even though the
  // network came back. Unlocking/foregrounding fires visibilitychange reliably, so that's
  // the actual reconnect signal on mobile (same reasoning as maybeAutoSync below).
  //
  // AND on a short interval while the app stays open and foregrounded: visibilitychange
  // and the online event both fire on a TRANSITION, so a queue built up because the
  // SERVER (not the phone's connectivity) was down never retries if the user just keeps
  // watching the app — the whole point of queueing is that it self-heals unattended.
  useEffect(() => {
    const trySync = async () => {
      if (!navigator.onLine) return;
      let synced = 0;
      if (await hasCreds().catch(() => false)) {
        const { pushed } = await pushMailbox().catch(() => ({ pushed: 0 }));
        synced += pushed;
      }
      const { sent } = await flushOutbox().catch(() => ({ sent: 0 }));
      synced += sent;
      if (synced > 0) {
        showToast(`Back online — ${synced} synced`);
        // Positive confirmation the queued offline work actually landed (Drive mailbox
        // and/or server-direct), not just that it was queued — same mechanism/permission
        // gate as the quit-nag / failed-capture alerts (quitNotify.js).
        showLocalNotification(
          synced === 1 ? '1 offline change synced' : `${synced} offline changes synced`,
          { body: 'Your offline grades/edits made it to the server.', tag: 'obsopt-outbox-sync' },
        );
      }
    };
    if (online) trySync();
    const onVis = () => { if (document.visibilityState === 'visible') trySync(); };
    document.addEventListener('visibilitychange', onVis);
    const id = setInterval(trySync, OUTBOX_RETRY_MS);
    return () => { document.removeEventListener('visibilitychange', onVis); clearInterval(id); };
  }, [online, showToast]);

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

  // "Are you a quitter?" nag, mobile flavor — see quitNotify.js for why this is a
  // notification-on-background instead of the web modal / desktop dialog.
  useEffect(() => armQuitNotify(isAuthenticated), [isAuthenticated]);

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
