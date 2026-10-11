import { forwardRef } from 'react';
import styles from './Input.module.css';

// Thin wrapper over <input>; the look comes from base.css. Adds `invalid`.
const Input = forwardRef(function Input({ invalid = false, className = '', ...rest }, ref) {
  return (
    <input
      ref={ref}
      aria-invalid={invalid || undefined}
      className={`${styles.input} ${className}`}
      {...rest}
    />
  );
});
export default Input;
