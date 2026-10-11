import styles from './Badge.module.css';

// tone: neutral | accent | ember | success | danger
export default function Badge({ tone = 'neutral', children, className = '' }) {
  return <span className={`${styles.badge} ${styles[tone] ?? ''} ${className}`}>{children}</span>;
}
