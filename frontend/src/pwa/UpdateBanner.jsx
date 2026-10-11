import { useEffect, useState } from 'react';
import { onUpdateAvailable, isUpdateReady, applyUpdate } from './registerSW';
import Button from '../components/atoms/Button';
import styles from './UpdateBanner.module.css';

// Persistent (not a toast) — stays until the user reloads. The old version keeps working
// until "Reload" hands control to the waiting service worker (registerSW.applyUpdate()).
export default function UpdateBanner() {
  const [ready, setReady] = useState(isUpdateReady);
  useEffect(() => onUpdateAvailable(() => setReady(true)), []);
  if (!ready) return null;
  return (
    <div className={styles.banner} role="status" aria-live="polite">
      <span className={styles.text}>Update ready</span>
      <Button size="sm" variant="primary" onClick={applyUpdate}>Reload</Button>
    </div>
  );
}
