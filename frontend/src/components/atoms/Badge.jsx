import styles from './Badge.module.css';

// tone: neutral | accent | ember | success | danger
export default function Badge({ tone = 'neutral', children, className = '', ...rest }) {
  return <span {...rest} className={`${styles.badge} ${styles[tone] ?? ''} ${className}`}>{children}</span>;
}
