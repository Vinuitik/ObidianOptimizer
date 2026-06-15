import { NavLink } from 'react-router-dom';
import Icon from '../components/atoms/Icon';
import styles from './BottomNav.module.css';

// Thumb-reachable tab bar for the mobile shell. To add a tab: add an entry here
// and a matching <Route> in MobileApp.jsx.
const TABS = [
  { to: '/',         icon: 'file',     label: 'Notes',   end: true },
  { to: '/review',   icon: 'clock',    label: 'Review' },
  { to: '/search',   icon: 'search',   label: 'Search' },
  { to: '/capture',  icon: 'link',     label: 'Capture' },
  { to: '/settings', icon: 'settings', label: 'Settings' },
];

export default function BottomNav() {
  return (
    <nav className={styles.nav} aria-label="Primary">
      {TABS.map(({ to, icon, label, end }) => (
        <NavLink
          key={to}
          to={to}
          end={end}
          className={({ isActive }) => `${styles.tab} ${isActive ? styles.active : ''}`}
        >
          <Icon name={icon} size={22} />
          <span className={styles.label}>{label}</span>
        </NavLink>
      ))}
    </nav>
  );
}
