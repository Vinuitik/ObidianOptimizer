import styles from './Checkbox.module.css';

// Checkbox with an inline label. Extra props go to the <input>.
export default function Checkbox({ label, className = '', id, ...rest }) {
  return (
    <label className={`${styles.wrap} ${className}`} htmlFor={id}>
      <input type="checkbox" id={id} className={styles.box} {...rest} />
      {label != null && <span className={styles.label}>{label}</span>}
    </label>
  );
}
