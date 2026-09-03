import useStore from '../store/useStore';
import RefreshButton from '../components/atoms/RefreshButton';
import styles from './TopBar.module.css';

// Slim utility strip for the installed PWA — holds the actions that used to be crammed
// into BottomNav's tab row (auth, refresh). BottomNav stays pure navigation.
export default function TopBar() {
  const isAuthenticated = useStore(s => s.isAuthenticated);
  const logout          = useStore(s => s.logout);
  const setShowLogin    = useStore(s => s.setShowLogin);

  return (
    <div className={styles.bar}>
      <span className={styles.brand}>Obsidian Optimizer</span>
      <div className={styles.actions}>
        <RefreshButton />
        <button
          type="button"
          className={styles.authBtn}
          onClick={isAuthenticated ? logout : () => setShowLogin(true)}
        >
          {isAuthenticated ? 'Sign out' : 'Sign in'}
        </button>
      </div>
    </div>
  );
}
