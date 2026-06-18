import { NavLink } from 'react-router-dom';
import useStore from '../../store/useStore';
import ObsidianMark from '../atoms/ObsidianMark';
import styles from './NavBar.module.css';

const NAV_ITEMS = [
  { to: '/',         label: 'Notes' },
  { to: '/learn',    label: 'Learn' },
  { to: '/review',   label: 'Review' },
  { to: '/dashboard', label: 'Dashboard' },
  { to: '/settings', label: 'Settings' },
];

export default function NavBar() {
  const isAuthenticated = useStore(s => s.isAuthenticated);
  const logout = useStore(s => s.logout);
  const setShowLogin = useStore(s => s.setShowLogin);

  return (
    <nav className={styles.nav}>
      <div className={styles.brand}>
        <ObsidianMark size={22} glow={false} />
        <span className={styles.brandText}>
          Obsidian<span className={styles.brandAccent}> Optimizer</span>
        </span>
      </div>

      <div className={styles.links}>
        {NAV_ITEMS.map(({ to, label }) => (
          <NavLink
            key={to}
            to={to}
            end={to === '/'}
            className={({ isActive }) =>
              `${styles.link} ${isActive ? styles.linkActive : ''}`
            }
          >
            {label}
          </NavLink>
        ))}
      </div>

      <div className={styles.right}>
        <span className={styles.avatar}>V</span>
        {isAuthenticated ? (
          <button className={styles.authBtn} onClick={logout}>Sign out</button>
        ) : (
          <button className={styles.authBtn} onClick={() => setShowLogin(true)}>Sign in</button>
        )}
      </div>
    </nav>
  );
}
