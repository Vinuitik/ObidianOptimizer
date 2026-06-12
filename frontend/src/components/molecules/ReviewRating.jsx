import { useState, useEffect, useRef } from 'react';
import useStore from '../../store/useStore';
import { gradeNote } from '../../api/notes';
import styles from './ReviewRating.module.css';

const OPTIONS = [
  { label: 'Very easy', value: 'VERY_EASY', cls: styles.easy },
  { label: 'Easy',      value: 'EASY',      cls: styles.easy },
  { label: 'Good',      value: 'GOOD',      cls: styles.good },
  { label: 'Hard',      value: 'HARD',      cls: styles.hard },
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
    setOpen(false);
    gradeNote(fullPath, value)
      .then(() => dismissFromReview(fullPath))
      .catch(err => console.error('grade failed:', err));
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
