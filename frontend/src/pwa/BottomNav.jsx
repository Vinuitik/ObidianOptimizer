import { NavLink } from 'react-router-dom';
import useStore from '../store/useStore';
import Icon from '../components/atoms/Icon';
import useDueCount from './useDueCount';
import styles from './BottomNav.module.css';

// Thumb-reachable tab bar for the installed PWA — pure navigation. Sync is NOT a tab: it
// lives behind the TopBar sync chip (/sync). To add a tab: an entry here + a matching
// <Route> in MobileApp.jsx.
export const TABS = [
  { to: '/review',  icon: 'clock',  label: 'Review' },
  { to: '/learn',   icon: 'inbox',  label: 'Learn' },
  { to: '/sources', icon: 'plus',   label: 'Sources' },
  { to: '/notes',   icon: 'file',   label: 'Notes' },
];

export default function BottomNav() {
  const due = useDueCount();
  const tabs = TABS;

  return (
    <nav className={styles.nav} aria-label="Primary">
      {tabs.map(({ to, icon, label }) => (
        <NavLink
          key={to}
          to={to}
          className={({ isActive }) => `${styles.tab} ${isActive ? styles.active : ''}`}
        >
          <span className={styles.iconWrap}>
            <Icon name={icon} size={22} />
            {to === '/review' && due > 0 && (
              <span className={styles.badge} aria-label={`${due} due`}>{due > 99 ? '99+' : due}</span>
            )}
          </span>
          <span className={styles.label}>{label}</span>
        </NavLink>
      ))}
    </nav>
  );
}
