import Icon from '../atoms/Icon';
import Button from '../atoms/Button';
import styles from './ErrorState.module.css';

// Human message + Retry. Never pass a bare status code as `message` ("Couldn't load failures").
export default function ErrorState({ message = "Couldn't load this", detail, onRetry, retryLabel = 'Retry', className = '' }) {
  return (
    <div className={`${styles.root} ${className}`} role="alert">
      <span className={styles.icon}><Icon name="alert" size={24} /></span>
      <p className={styles.msg}>{message}</p>
      {detail && <p className={styles.detail}>{detail}</p>}
      {onRetry && <Button onClick={onRetry}>{retryLabel}</Button>}
    </div>
  );
}
