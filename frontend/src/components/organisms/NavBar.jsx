import { NavLink } from 'react-router-dom';
import useStore from '../../store/useStore';
import ObsidianMark from '../atoms/ObsidianMark';
import styles from './NavBar.module.css';

const NAV_ITEMS = [
  { to: '/',         label: 'Notes' },
  { to: '/learn',    label: 'Learn' },
  { to: '/review',   label: 'Review', flashcardsOnly: true },
  { to: '/dashboard', label: 'Dashboard' },
  { to: '/settings', label: 'Settings' },
];

export default function NavBar() {
  const isAuthenticated = useStore(s => s.isAuthenticated);
  const logout = useStore(s => s.logout);
  const setShowLogin = useStore(s => s.setShowLogin);
  // Flashcards off → the review-list system runs inline on the Notes page, so the
  // dedicated flashcard Review tab disappears (mutually exclusive systems).
  const flashcardsEnabled = useStore(s => s.settings.flashcardsEnabled ?? true);
  const items = NAV_ITEMS.filter(it => !it.flashcardsOnly || flashcardsEnabled);

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
