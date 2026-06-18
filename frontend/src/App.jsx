import { BrowserRouter, Routes, Route, useLocation } from 'react-router-dom';
import { useEffect } from 'react';
import { AnimatePresence, motion } from 'framer-motion';
import MainPage from './pages/MainPage';
import SettingsPage from './pages/SettingsPage';
import ReviewPage from './pages/ReviewPage';
import LearnPage from './pages/LearnPage';
import DashboardPage from './pages/DashboardPage';
import NavBar from './components/organisms/NavBar';
import LoginModal from './components/organisms/LoginModal';
import Toast from './components/atoms/Toast';
import useStore from './store/useStore';
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
          <Route path="/dashboard" element={<DashboardPage />} />
          <Route path="/settings" element={<SettingsPage />} />
        </Routes>
      </motion.div>
    </AnimatePresence>
  );
}

export default function App() {
  const checkAuth  = useStore(s => s.checkAuth);
  const showLogin  = useStore(s => s.showLogin);

  useEffect(() => { checkAuth(); }, []);

  return (
    <BrowserRouter>
      <div className={styles.shell}>
        <NavBar />
        <AnimatedRoutes />
        {showLogin && <LoginModal />}
        <Toast />
      </div>
    </BrowserRouter>
  );
}
