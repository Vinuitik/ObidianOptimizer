import { useState } from 'react';
import { NavLink, useLocation } from 'react-router-dom';
import useStore from '../../store/useStore';
import Icon from '../atoms/Icon';
import useNavItems from './useNavItems';
import styles from './MobileTabBar.module.css';

// Phone-only bottom navigation for the full website (the installed PWA has its own
// BottomNav). Primary pages are one tap; the rest + sign in/out live in a "More" sheet.
export default function MobileTabBar() {
  const items = useNavItems();
  const { pathname } = useLocation();
  const isAuthenticated = useStore(s => s.isAuthenticated);
  const logout = useStore(s => s.logout);
  const setShowLogin = useStore(s => s.setShowLogin);
  const [moreOpen, setMoreOpen] = useState(false);

  const primary = items.filter(it => it.primary);
  const secondary = items.filter(it => !it.primary);
  const moreActive = secondary.some(it => it.to === pathname);

  return (
    <>
      {moreOpen && (
        <>
          <div className={styles.scrim} onClick={() => setMoreOpen(false)} aria-hidden="true" />
          <div className={styles.sheet} role="menu">
            {secondary.map(({ to, label }) => (
              <NavLink
                key={to}
                to={to}
                role="menuitem"
                onClick={() => setMoreOpen(false)}
                className={({ isActive }) => `${styles.sheetItem} ${isActive ? styles.sheetItemActive : ''}`}
              >
                {label}
              </NavLink>
            ))}
            <button
              type="button"
              role="menuitem"
              className={styles.sheetItem}
              onClick={() => { setMoreOpen(false); isAuthenticated ? logout() : setShowLogin(true); }}
            >
              {isAuthenticated ? 'Sign out' : 'Sign in'}
            </button>
          </div>
        </>
      )}
      <nav className={styles.bar} aria-label="Main">
        {primary.map(({ to, label, icon }) => (
          <NavLink
            key={to}
            to={to}
            end={to === '/'}
            onClick={() => setMoreOpen(false)}
            className={({ isActive }) => `${styles.tab} ${isActive ? styles.tabActive : ''}`}
          >
            <Icon name={icon} size={22} />
            <span>{label}</span>
          </NavLink>
        ))}
        <button
          type="button"
          className={`${styles.tab} ${moreActive || moreOpen ? styles.tabActive : ''}`}
          onClick={() => setMoreOpen(o => !o)}
          aria-expanded={moreOpen}
        >
          <span className={styles.moreDots} aria-hidden="true">•••</span>
          <span>More</span>
        </button>
      </nav>
    </>
  );
}
