import { NavLink } from 'react-router-dom';
import useStore from '../../store/useStore';
import styles from './NavBar.module.css';

const NAV_ITEMS = [
  { to: '/',        label: 'Notes' },
  { to: '/review',  label: 'Review' },
  { to: '/settings', label: 'Settings' },
];

export default function NavBar() {
  const isAuthenticated = useStore(s => s.isAuthenticated);
  const logout = useStore(s => s.logout);
  const setShowLogin = useStore(s => s.setShowLogin);

  return (
    <nav className={styles.nav}>
      <span className={styles.brand}>Obsidian</span>

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

      <div className={styles.auth}>
        {isAuthenticated ? (
          <button className={styles.authBtn} onClick={logout}>Sign out</button>
        ) : (
          <button className={styles.authBtn} onClick={() => setShowLogin(true)}>Sign in</button>
        )}
      </div>
    </nav>
  );
}
