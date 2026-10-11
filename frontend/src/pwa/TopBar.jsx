import { Link, useLocation } from 'react-router-dom';
import useStore from '../store/useStore';
import RefreshButton from '../components/atoms/RefreshButton';
import Badge from '../components/atoms/Badge';
import Button from '../components/atoms/Button';
import useSyncStatus from './syncStatus';
import styles from './TopBar.module.css';

// Route -> page title shown instead of the brand. Unknown paths fall back to the app name.
export const TITLES = {
  '/review': 'Review', '/learn': 'Learn', '/capture': 'Capture', '/tracks': 'Tracks',
  '/notes': 'Notes', '/search': 'Search', '/sync': 'Sync', '/settings': 'Sync',
};
export const titleFor = (pathname) => TITLES[pathname] ?? 'Obsidian Optimizer';

// Slim utility strip for the installed PWA: page title, sync chip (opens /sync),
// refresh, sign in/out. BottomNav stays pure navigation.
export default function TopBar() {
  const { pathname } = useLocation();
  const isAuthenticated = useStore(s => s.isAuthenticated);
  const logout          = useStore(s => s.logout);
  const setShowLogin    = useStore(s => s.setShowLogin);
  const sync            = useSyncStatus();

  return (
    <div className={styles.bar}>
      <h1 className={styles.title}>{titleFor(pathname)}</h1>
      <div className={styles.actions}>
        <Link to="/sync" className={styles.chip} data-state={sync.state} aria-label={`Sync status: ${sync.label}. Open sync.`}>
          <Badge tone={sync.tone}>{sync.label}</Badge>
        </Link>
        <RefreshButton />
        <Button
          size="sm"
          variant="ghost"
          onClick={isAuthenticated ? logout : () => setShowLogin(true)}
        >
          {isAuthenticated ? 'Sign out' : 'Sign in'}
        </Button>
      </div>
    </div>
  );
}
