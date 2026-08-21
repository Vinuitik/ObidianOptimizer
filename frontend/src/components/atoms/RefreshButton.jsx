import { useEffect, useState } from 'react';
import Icon from './Icon';
import { onUpdateAvailable, checkForUpdate, reloadApp, applyUpdate } from '../../pwa/registerSW';
import styles from './RefreshButton.module.css';

// A real reload button — SPA route changes never re-fetch index.html/JS, so this was
// the missing "get me the latest deploy" affordance (previously: reinstall the PWA).
// Doubles as the update-available prompt: once a new service worker has downloaded and
// is WAITING (not yet active — see registerSW.js), the icon-only button expands to an
// "Update" label. Clicking it in that state is what actually hands control to the new
// SW (applyUpdate()); the old version keeps working right up until that click.
export default function RefreshButton() {
  const [updateReady, setUpdateReady] = useState(false);

  useEffect(() => onUpdateAvailable(() => setUpdateReady(true)), []);

  const handleClick = () => {
    if (updateReady) { applyUpdate(); return; }
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
