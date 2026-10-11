import Icon from './Icon';
import styles from './IconButton.module.css';

// aria-label is REQUIRED (icon-only control) — throws if missing.
export default function IconButton({
  icon,
  'aria-label': ariaLabel,
  onClick,
  size = 'md',
  variant = 'ghost',
  className = '',
  type = 'button',
  disabled = false,
  ...rest
}) {
  if (!ariaLabel) throw new Error('IconButton requires an aria-label');
  return (
    <button
      type={type}
      aria-label={ariaLabel}
      title={ariaLabel}
      onClick={onClick}
      disabled={disabled}
      className={`${styles.btn} ${styles[variant] ?? ''} ${size === 'sm' ? styles.sm : ''} ${className}`}
      {...rest}
    >
      <Icon name={icon} size={size === 'sm' ? 14 : 18} />
    </button>
  );
}
