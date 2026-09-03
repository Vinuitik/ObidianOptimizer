import { NavLink } from 'react-router-dom';
import useStore from '../store/useStore';
import Icon from '../components/atoms/Icon';
import styles from './BottomNav.module.css';

// Thumb-reachable tab bar for the installed PWA — pure navigation (auth + refresh live
// in TopBar instead, see MobileLayout.jsx). Deliberately only the app's real jobs —
// no Notes/Search (those are on the full site). To add a tab: an entry here + a
// matching <Route> in MobileApp.jsx.
const TABS = [
  { to: '/review',   icon: 'clock',    label: 'Review' },
  { to: '/learn',    icon: 'file',     label: 'Learn' },
  { to: '/capture',  icon: 'link',     label: 'Capture' },
  { to: '/tracks',   icon: 'track',    label: 'Tracks', tracksOnly: true },
  { to: '/settings', icon: 'settings', label: 'Sync' },
];

export default function BottomNav() {
  // Tracks off → tab hidden, but /tracks stays reachable directly (mirrors NavBar).
  const tracksEnabled = useStore(s => s.settings.tracksEnabled ?? true);
  const tabs = TABS.filter(t => !t.tracksOnly || tracksEnabled);

  return (
    <nav className={styles.nav} aria-label="Primary">
      {tabs.map(({ to, icon, label }) => (
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
