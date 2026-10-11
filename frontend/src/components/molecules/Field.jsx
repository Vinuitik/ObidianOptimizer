import { Children, cloneElement, isValidElement, useId } from 'react';
import styles from './Field.module.css';

// <Field label hint error><Input/></Field> — wires the single child's id, aria-describedby
// (hint + error) and invalid state. Works with Input / Select / Textarea / native controls.
export default function Field({ label, hint, error, children, className = '' }) {
  const uid = useId();
  const child = Children.only(children);
  const id = (isValidElement(child) && child.props.id) || `f${uid}`;
  const hintId = hint ? `${id}-hint` : undefined;
  const errId = error ? `${id}-err` : undefined;
  const describedBy = [child.props['aria-describedby'], hintId, errId].filter(Boolean).join(' ') || undefined;

  const control = cloneElement(child, {
    id,
    'aria-describedby': describedBy,
    'aria-invalid': error ? true : child.props['aria-invalid'],
  });

  return (
    <div className={`${styles.field} ${className}`}>
      {label && <label className={styles.label} htmlFor={id}>{label}</label>}
      {control}
      {hint && !error && <p id={hintId} className={styles.hint}>{hint}</p>}
      {error && <p id={errId} className={styles.error} role="alert">{error}</p>}
    </div>
  );
}
