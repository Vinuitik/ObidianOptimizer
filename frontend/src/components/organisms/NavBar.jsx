import { useEffect, useRef, useState } from 'react';
import { NavLink, useNavigate } from 'react-router-dom';
import useStore from '../../store/useStore';
import ObsidianMark from '../atoms/ObsidianMark';
import Button from '../atoms/Button';
import Icon from '../atoms/Icon';
import RefreshButton from '../atoms/RefreshButton';
import { checkForUpdate, reloadApp } from '../../pwa/registerSW';
import QuickCapture from '../molecules/QuickCapture';
import VaultSwitcher from '../molecules/VaultSwitcher';
import useNavItems from './useNavItems';
import styles from './NavBar.module.css';

export default function NavBar() {
  const isAuthenticated = useStore(s => s.isAuthenticated);
  const logout = useStore(s => s.logout);
  const setShowLogin = useStore(s => s.setShowLogin);
  const items = useNavItems();
  const navigate = useNavigate();
  const [menuOpen, setMenuOpen] = useState(false);
  const menuRef = useRef(null);
  useEffect(() => {
    if (!menuOpen) return undefined;
    const onDown = e => { if (!menuRef.current?.contains(e.target)) setMenuOpen(false); };
    const onKey = e => { if (e.key === 'Escape') setMenuOpen(false); };
    document.addEventListener('mousedown', onDown);
    document.addEventListener('keydown', onKey);
    return () => { document.removeEventListener('mousedown', onDown); document.removeEventListener('keydown', onKey); };
  }, [menuOpen]);
  const run = fn => () => { setMenuOpen(false); fn(); };

  return (
    <nav className={styles.nav}>
      <div className={styles.brand}>
        <ObsidianMark size={22} glow={false} />
        <span className={styles.brandText}>
          Obsidian<span className={styles.brandAccent}> Optimizer</span>
        </span>
      </div>

      <div className={styles.links}>
        {items.filter(it => it.primary).map(({ to, label }) => (
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
        {isAuthenticated && <VaultSwitcher />}
        {isAuthenticated && <QuickCapture />}
        <span className={styles.phoneOnly}><RefreshButton /></span>
        {isAuthenticated ? (
          <div className={styles.menuWrap} ref={menuRef}>
            <button
              type="button"
              className={styles.avatar}
              aria-haspopup="menu"
              aria-expanded={menuOpen}
              aria-label="Account menu"
              onClick={() => setMenuOpen(o => !o)}
            >V</button>
            {menuOpen && (
              <div className={styles.menu} role="menu">
                <button type="button" role="menuitem" className={styles.menuItem} onClick={run(() => navigate('/settings'))}>
                  <Icon name="settings" size={15} /> Settings
                </button>
                <button type="button" role="menuitem" className={styles.menuItem}
                  onClick={run(() => checkForUpdate().finally(() => setTimeout(reloadApp, 300)))}>
                  <Icon name="refresh" size={15} /> Refresh
                </button>
                <button type="button" role="menuitem" className={styles.menuItem} onClick={run(logout)}>
                  <Icon name="user" size={15} /> Sign out
                </button>
              </div>
            )}
          </div>
        ) : (
          <Button variant="secondary" size="sm" onClick={() => setShowLogin(true)}>Sign in</Button>
        )}
      </div>
    </nav>
  );
}
