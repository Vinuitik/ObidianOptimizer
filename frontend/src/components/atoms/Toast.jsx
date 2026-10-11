import useStore from '../../store/useStore';
import styles from './Toast.module.css';

// The ONE feedback mechanism. showToast('text') or
// showToast({ message, action: { label, onClick }, tone: 'neutral'|'success'|'error' }).
export default function Toast() {
  const toast      = useStore(s => s.toast);
  const clearToast = useStore(s => s.clearToast);

  if (!toast) return null;
  const { message, action, tone = 'neutral' } = toast;

  return (
    <div
      className={`${styles.toast} ${styles[tone] ?? ''}`}
      role={tone === 'error' ? 'alert' : 'status'}
      aria-live={tone === 'error' ? 'assertive' : 'polite'}
    >
      <span className={styles.message}>{message}</span>
      {action && (
        <button
          type="button"
          className={styles.action}
          onClick={() => { clearToast(); action.onClick?.(); }}
        >
          {action.label}
        </button>
      )}
      <button type="button" className={styles.close} onClick={clearToast} aria-label="Dismiss">×</button>
    </div>
  );
}
