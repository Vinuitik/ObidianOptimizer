import { NavLink } from 'react-router-dom';
import Icon from '../components/atoms/Icon';
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
    </nav>
  );
}
