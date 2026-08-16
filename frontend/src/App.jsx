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
import NavBar from './components/organisms/NavBar';
import LoginModal from './components/organisms/LoginModal';
import SyncBanner from './components/organisms/SyncBanner';
import QuitGuard from './components/organisms/QuitGuard';
import Toast from './components/atoms/Toast';
import useStore from './store/useStore';
import { flushOutbox } from './pwa/offlineApi';
import { onConnectivityChange } from './pwa/connectivity';
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
  // Replay them on load and whenever connectivity returns, so nothing is stranded
  // on the full site either (no-op when the outbox is empty).
  useEffect(() => {
    flushOutbox().catch(() => {});
    return onConnectivityChange(on => { if (on) flushOutbox().catch(() => {}); });
  }, []);

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
