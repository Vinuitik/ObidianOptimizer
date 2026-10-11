import { useRef } from 'react';
import styles from './SegmentedTabs.module.css';

// tabs: [{ id, label, badge? }]. Controlled via value/onChange. Arrow/Home/End keys move focus+selection.
export default function SegmentedTabs({ tabs, value, onChange, 'aria-label': ariaLabel = 'Sections', className = '' }) {
  const refs = useRef([]);

  const onKeyDown = (e, i) => {
    let next = null;
    if (e.key === 'ArrowRight') next = (i + 1) % tabs.length;
    else if (e.key === 'ArrowLeft') next = (i - 1 + tabs.length) % tabs.length;
    else if (e.key === 'Home') next = 0;
    else if (e.key === 'End') next = tabs.length - 1;
    if (next == null) return;
    e.preventDefault();
    onChange(tabs[next].id);
    refs.current[next]?.focus();
  };

  return (
    <div className={`${styles.root} ${className}`} role="tablist" aria-label={ariaLabel}>
      {tabs.map((t, i) => {
        const active = t.id === value;
        return (
          <button
            key={t.id}
            ref={el => { refs.current[i] = el; }}
            type="button"
            role="tab"
            aria-selected={active}
            tabIndex={active ? 0 : -1}
            className={`${styles.tab} ${active ? styles.active : ''}`}
            onClick={() => onChange(t.id)}
            onKeyDown={e => onKeyDown(e, i)}
          >
            {t.label}
            {t.badge != null && <span className={styles.badge}>{t.badge}</span>}
          </button>
        );
      })}
    </div>
  );
}
