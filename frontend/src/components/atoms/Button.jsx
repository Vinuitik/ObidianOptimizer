import Icon from './Icon';
import Spinner from './Spinner';
import styles from './Button.module.css';

// variant: primary | secondary | ghost | danger  ('default' = legacy alias of secondary)
// size: sm | md.  loading: shows a spinner, disables the button.  icon: Icon name (leading).
export default function Button({
  onClick,
  children,
  variant = 'secondary',
  size = 'md',
  loading = false,
  icon,
  className = '',
  type = 'button',
  disabled = false,
  ...rest
}) {
  const v = variant === 'default' ? 'secondary' : variant;
  return (
    <button
      type={type}
      className={`${styles.btn} ${styles[v] ?? ''} ${size === 'sm' ? styles.sm : ''} ${className}`}
      onClick={onClick}
      disabled={disabled || loading}
      aria-busy={loading || undefined}
      {...rest}
    >
      {loading ? <Spinner size={14} label="Working" /> : icon ? <Icon name={icon} size={16} /> : null}
      {children}
    </button>
  );
}
