import styles from './Button.module.css';

export default function Button({ onClick, children, variant = 'default', className = '', type = 'button' }) {
  return (
    <button
      type={type}
      className={`${styles.btn} ${styles[variant] ?? ''} ${className}`}
      onClick={onClick}
    >
      {children}
    </button>
  );
}
