import { useState, useEffect, useRef } from 'react';
import useStore from '../../store/useStore';
import styles from './ReviewRating.module.css';

const OPTIONS = [
  { label: 'Easy', value: 'easy', cls: styles.easy },
  { label: 'Good', value: 'good', cls: styles.good },
  { label: 'Hard', value: 'hard', cls: styles.hard },
];

export default function ReviewRating({ fullPath }) {
  const [open, setOpen] = useState(false);
  const dismissFromReview = useStore(s => s.dismissFromReview);
  const wrapperRef = useRef(null);

  useEffect(() => {
    if (!open) return;
    function handleClickOutside(e) {
      if (wrapperRef.current && !wrapperRef.current.contains(e.target)) {
        setOpen(false);
      }
    }
    document.addEventListener('mousedown', handleClickOutside);
    return () => document.removeEventListener('mousedown', handleClickOutside);
  }, [open]);

  function handleRate(e, value) {
    e.stopPropagation();
    dismissFromReview(fullPath);
    setOpen(false);
    // TODO: POST rating to backend when API is ready
  }

  return (
    <div className={styles.wrapper} ref={wrapperRef}>
      <button
        className={styles.trigger}
        onClick={e => { e.stopPropagation(); setOpen(o => !o); }}
        title="Rate this note"
      >
        Rate ▾
      </button>
      {open && (
        <div className={styles.dropdown}>
          {OPTIONS.map(({ label, value, cls }) => (
            <button
              key={value}
              className={`${styles.opt} ${cls}`}
              onClick={e => handleRate(e, value)}
            >
              {label}
            </button>
          ))}
        </div>
      )}
    </div>
  );
}
