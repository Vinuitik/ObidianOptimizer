import { NavLink } from 'react-router-dom';
import useStore from '../store/useStore';
import Icon from '../components/atoms/Icon';
import RefreshButton from '../components/atoms/RefreshButton';
import styles from './BottomNav.module.css';

// Thumb-reachable tab bar for the installed PWA. Deliberately only the app's four
// jobs — no Notes/Search (those are on the full site). To add a tab: an entry here
// + a matching <Route> in MobileApp.jsx.
const TABS = [
  { to: '/review',   icon: 'clock',    label: 'Review' },
  { to: '/learn',    icon: 'file',     label: 'Learn' },
  { to: '/capture',  icon: 'link',     label: 'Capture' },
  { to: '/settings', icon: 'settings', label: 'Sync' },
];

export default function BottomNav() {
  const isAuthenticated = useStore(s => s.isAuthenticated);
  const logout          = useStore(s => s.logout);
  const setShowLogin    = useStore(s => s.setShowLogin);

  return (
    <nav className={styles.nav} aria-label="Primary">
      {TABS.map(({ to, icon, label }) => (
        <NavLink
          key={to}
          to={to}
          className={({ isActive }) => `${styles.tab} ${isActive ? styles.active : ''}`}
        >
          <Icon name={icon} size={22} />
          <span className={styles.label}>{label}</span>
        </NavLink>
      ))}
      {/* Auth is a bottom-nav selection too, not just the Sync tab — so signing in/out is
          always one tap away even after the auto-login modal is dismissed. */}
      <button
        type="button"
        className={`${styles.tab} ${styles.authTab}`}
        onClick={isAuthenticated ? logout : () => setShowLogin(true)}
      >
        <Icon name="user" size={22} />
        <span className={styles.label}>{isAuthenticated ? 'Sign out' : 'Sign in'}</span>
      </button>
      <div className={styles.refreshSlot}>
        <RefreshButton />
      </div>
    </nav>
  );
}
