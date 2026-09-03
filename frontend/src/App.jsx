import { BrowserRouter, Routes, Route, useLocation } from 'react-router-dom';
import { useEffect } from 'react';
import { AnimatePresence, motion } from 'framer-motion';
import MainPage from './pages/MainPage';
import SettingsPage from './pages/SettingsPage';
import ReviewPage from './pages/ReviewPage';
import TracksPage from './pages/TracksPage';
import LearnPage from './pages/LearnPage';
import DashboardPage from './pages/DashboardPage';
import GetAppPage from './pages/GetAppPage';
import PipelineFailuresPage from './pages/PipelineFailuresPage';
import NavBar from './components/organisms/NavBar';
import LoginModal from './components/organisms/LoginModal';
import SyncBanner from './components/organisms/SyncBanner';
import QuitGuard from './components/organisms/QuitGuard';
import Toast from './components/atoms/Toast';
import useStore from './store/useStore';
import { flushOutbox, OUTBOX_RETRY_MS } from './pwa/offlineApi';
import { pushMailbox } from './pwa/mailbox';
import { hasCreds } from './pwa/setup';
import { onConnectivityChange } from './pwa/connectivity';
import { showLocalNotification } from './pwa/quitNotify';
import styles from './App.module.css';

const pageVariants = {
  initial: { opacity: 0 },
  animate: { opacity: 1, transition: { duration: 0.18, ease: 'easeOut' } },
  exit:    { opacity: 0, transition: { duration: 0.18, ease: 'easeIn' } },
};

function AnimatedRoutes() {
  const location = useLocation();
  return (
    <AnimatePresence mode="wait">
      <motion.div
        key={location.pathname}
        className={styles.pageWrapper}
        variants={pageVariants}
        initial="initial"
        animate="animate"
        exit="exit"
      >
        <Routes location={location}>
          <Route path="/" element={<MainPage />} />
          <Route path="/learn" element={<LearnPage />} />
          <Route path="/review" element={<ReviewPage />} />
          <Route path="/tracks" element={<TracksPage />} />
          <Route path="/dashboard" element={<DashboardPage />} />
          <Route path="/failures" element={<PipelineFailuresPage />} />
          <Route path="/get-app" element={<GetAppPage />} />
          <Route path="/settings" element={<SettingsPage />} />
        </Routes>
      </motion.div>
    </AnimatePresence>
  );
}

export default function App() {
  const checkAuth  = useStore(s => s.checkAuth);
  const showLogin  = useStore(s => s.showLogin);
  const showToast  = useStore(s => s.showToast);

  // Re-validate on mount AND whenever the tab regains focus, so a backend restart
  // (session cookie invalidated) is detected proactively — not left showing stale
  // "Sign out" + loaded data until the next action happens to 401.
  useEffect(() => {
    checkAuth();
    const onFocus = () => checkAuth();
    window.addEventListener('focus', onFocus);
    return () => window.removeEventListener('focus', onFocus);
  }, [checkAuth]);

  // Grades/captures made during a network blip queue to the outbox (offlineApi).
  // Replay them on load, whenever connectivity returns, on visibilitychange (a
  // backgrounded/frozen tab can miss the 'online' event entirely — see MobileLayout.jsx
  // for the mobile case this actually surfaced in), AND on a short interval while the tab
  // stays open and foregrounded — the previous three triggers all fire on some transition
  // (reconnect, refocus), so a queue built up because the SERVER was down (5xx/530, not a
  // device connectivity change — see offlineApi.js isServerUnreachable) never got retried
  // if the user just kept watching the app the whole time. The interval closes that gap.
  //
  // Drive-linked devices also get pushMailbox() first, same as MobileLayout.jsx: this
  // laptop isn't the server, and a laptop that's online while the SERVER is down should
  // drain to Drive, not just keep retrying a dead origin. Push-side only — desktop always
  // reads review data live from the server (useStore.fetchReviewNotes), so there's no
  // Drive-pull/driveMode flip here; that's scoped to the installed-PWA offline set.
  useEffect(() => {
    const flushAndNotify = async () => {
      if (await hasCreds().catch(() => false)) await pushMailbox().catch(() => {});
      const { sent } = await flushOutbox();
      if (sent > 0) {
        showToast(`Back online — ${sent} synced`);
        // Positive confirmation that queued offline work actually landed on the server —
        // not just that it was queued. Same permission-gated local-notification mechanism
        // as the quit-nag / failed-capture alerts (quitNotify.js); no-ops if not granted.
        showLocalNotification(
          sent === 1 ? '1 offline change synced' : `${sent} offline changes synced`,
          { body: 'Your offline grades/captures made it to the server.', tag: 'obsopt-outbox-sync' },
        );
      }
    };
    flushAndNotify().catch(() => {});
    const onVis = () => { if (document.visibilityState === 'visible' && navigator.onLine) flushAndNotify().catch(() => {}); };
    document.addEventListener('visibilitychange', onVis);
    const offConnectivity = onConnectivityChange(on => { if (on) flushAndNotify().catch(() => {}); });
    const id = setInterval(() => { if (navigator.onLine) flushAndNotify().catch(() => {}); }, OUTBOX_RETRY_MS);
    return () => {
      document.removeEventListener('visibilitychange', onVis);
      offConnectivity();
      clearInterval(id);
    };
  }, [showToast]);

  return (
    <BrowserRouter>
      <div className={styles.shell}>
        <NavBar />
        <SyncBanner />
        <AnimatedRoutes />
        {showLogin && <LoginModal />}
        <QuitGuard />
        <Toast />
      </div>
    </BrowserRouter>
  );
}
