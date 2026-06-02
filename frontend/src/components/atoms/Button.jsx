import styles from './Button.module.css';

export default function Button({ onClick, children, variant = 'default', className = '' }) {
  return (
    <button
      className={`${styles.btn} ${styles[variant] ?? ''} ${className}`}
      onClick={onClick}
    >
      {children}
    </button>
  );
}
