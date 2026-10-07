import { NavLink } from 'react-router-dom';
import useStore from '../../store/useStore';
import ObsidianMark from '../atoms/ObsidianMark';
import RefreshButton from '../atoms/RefreshButton';
import useNavItems from './useNavItems';
import styles from './NavBar.module.css';

export default function NavBar() {
  const isAuthenticated = useStore(s => s.isAuthenticated);
  const logout = useStore(s => s.logout);
  const setShowLogin = useStore(s => s.setShowLogin);
  const items = useNavItems();

  return (
    <nav className={styles.nav}>
      <div className={styles.brand}>
        <ObsidianMark size={22} glow={false} />
        <span className={styles.brandText}>
          Obsidian<span className={styles.brandAccent}> Optimizer</span>
        </span>
      </div>

      <div className={styles.links}>
        {items.map(({ to, label }) => (
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
        <RefreshButton />
        <span className={styles.avatar}>V</span>
        {isAuthenticated ? (
          <button className={`${styles.authBtn} ${styles.authOut}`} onClick={logout}>Sign out</button>
        ) : (
          <button className={styles.authBtn} onClick={() => setShowLogin(true)}>Sign in</button>
        )}
      </div>
    </nav>
  );
}
