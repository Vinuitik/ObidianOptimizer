import { forwardRef } from 'react';
import styles from './Select.module.css';

// Native <select>; pass <option> children, or options=[{value,label}].
const Select = forwardRef(function Select({ invalid = false, options, children, className = '', ...rest }, ref) {
  return (
    <select
      ref={ref}
      aria-invalid={invalid || undefined}
      className={`${styles.select} ${className}`}
      {...rest}
    >
      {options
        ? options.map(o => <option key={o.value} value={o.value}>{o.label}</option>)
        : children}
    </select>
  );
});
export default Select;
