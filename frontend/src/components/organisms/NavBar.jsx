import { useState } from 'react';
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

  // Mobile: the links row is collapsed behind a hamburger so the header stays a
  // single slim strip (brand + auth) instead of eating a quarter of the screen.
  // Desktop ignores this — the .menuBtn is display:none and .links is always shown.
  const [menuOpen, setMenuOpen] = useState(false);

  return (
    <nav className={styles.nav}>
      <div className={styles.brand}>
        <button
          className={styles.menuBtn}
          onClick={() => setMenuOpen(o => !o)}
          aria-label={menuOpen ? 'Close menu' : 'Open menu'}
          aria-expanded={menuOpen}
        >
          {menuOpen ? '✕' : '☰'}
        </button>
        <ObsidianMark size={22} glow={false} />
        <span className={styles.brandText}>
          Obsidian<span className={styles.brandAccent}> Optimizer</span>
        </span>
      </div>

      <div className={`${styles.links} ${menuOpen ? styles.linksOpen : ''}`}>
        {items.map(({ to, label }) => (
          <NavLink
            key={to}
            to={to}
            end={to === '/'}
            onClick={() => setMenuOpen(false)}
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
