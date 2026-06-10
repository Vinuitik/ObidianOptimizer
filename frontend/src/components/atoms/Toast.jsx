import useStore from '../../store/useStore';
import styles from './Toast.module.css';

export default function Toast() {
  const toast     = useStore(s => s.toast);
  const clearToast = useStore(s => s.clearToast);

  if (!toast) return null;

  return (
    <div className={styles.toast} role="alert">
      <span className={styles.message}>{toast.message}</span>
      <button className={styles.close} onClick={clearToast} aria-label="Dismiss">×</button>
    </div>
  );
}
