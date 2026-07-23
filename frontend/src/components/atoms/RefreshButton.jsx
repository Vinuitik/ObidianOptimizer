import { useEffect, useState } from 'react';
import Icon from './Icon';
import { onUpdateAvailable, checkForUpdate, reloadApp } from '../../pwa/registerSW';
import styles from './RefreshButton.module.css';

// A real reload button — SPA route changes never re-fetch index.html/JS, so this was
// the missing "get me the latest deploy" affordance (previously: reinstall the PWA).
// Doubles as the update-available prompt: once the service worker reports a new
// version took over, the icon-only button expands to an "Update" label; either way
// clicking it forces a fresh navigation (network-first in sw.js `handleNavigate`).
export default function RefreshButton() {
  const [updateReady, setUpdateReady] = useState(false);

  useEffect(() => onUpdateAvailable(() => setUpdateReady(true)), []);

  const handleClick = () => {
    if (updateReady) { reloadApp(); return; }
    checkForUpdate().finally(() => setTimeout(reloadApp, 300));
  };

  return (
    <button
      type="button"
      className={`${styles.btn} ${updateReady ? styles.ready : ''}`}
      onClick={handleClick}
      title={updateReady ? 'Update available — click to reload' : 'Refresh'}
      aria-label={updateReady ? 'Update available, reload' : 'Refresh'}
    >
      <Icon name="refresh" size={16} />
      {updateReady && <span className={styles.label}>Update</span>}
    </button>
  );
}
