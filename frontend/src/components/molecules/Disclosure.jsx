import { useId, useState } from 'react';
import { AnimatePresence, motion, useReducedMotion } from 'framer-motion';
import Icon from '../atoms/Icon';
import { collapse } from '../motion/presets';
import styles from './Disclosure.module.css';

const KEY = 'disclosure:';
const read = (k, d) => { try { const v = localStorage.getItem(KEY + k); return v == null ? d : v === '1'; } catch { return d; } };
const write = (k, v) => { try { localStorage.setItem(KEY + k, v ? '1' : '0'); } catch { /* storage unavailable */ } };

// Animated show/hide. Pass `storageKey` to remember open state per key in localStorage.
export default function Disclosure({ title, children, storageKey, defaultOpen = false, className = '' }) {
  const [open, setOpen] = useState(() => (storageKey ? read(storageKey, defaultOpen) : defaultOpen));
  const reduce = useReducedMotion();
  const panelId = useId();

  const toggle = () => {
    setOpen(o => { const n = !o; if (storageKey) write(storageKey, n); return n; });
  };

  return (
    <div className={`${styles.root} ${className}`}>
      <button
        type="button"
        className={styles.head}
        onClick={toggle}
        aria-expanded={open}
        aria-controls={panelId}
      >
        <span className={`${styles.chev} ${open ? styles.open : ''}`}><Icon name="chevron" size={14} /></span>
        {title}
      </button>
      <AnimatePresence initial={false}>
        {open && (
          <motion.div
            id={panelId}
            className={styles.body}
            variants={reduce ? undefined : collapse}
            initial={reduce ? false : 'initial'}
            animate="animate"
            exit={reduce ? undefined : 'exit'}
            style={{ overflow: 'hidden' }}
          >
            <div className={styles.inner}>{children}</div>
          </motion.div>
        )}
      </AnimatePresence>
    </div>
  );
}
