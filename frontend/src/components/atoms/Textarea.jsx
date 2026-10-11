import { forwardRef } from 'react';
import styles from './Textarea.module.css';

const Textarea = forwardRef(function Textarea({ invalid = false, className = '', rows = 4, ...rest }, ref) {
  return (
    <textarea
      ref={ref}
      rows={rows}
      aria-invalid={invalid || undefined}
      className={`${styles.textarea} ${className}`}
      {...rest}
    />
  );
});
export default Textarea;
