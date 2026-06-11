import { useRef, useState, useCallback } from 'react';
import styles from './LearnLayout.module.css';

// orientation: 'vertical' (left|right) | 'horizontal' (top|bottom)
// Default slot A = resource, slot B = note.
// swapped flips which slot renders in which position.
export default function LearnLayout({ orientation = 'vertical', slotA, slotB }) {
  const [swapped, setSwapped]     = useState(false);
  const [splitPct, setSplitPct]   = useState(50);
  const dragging = useRef(false);
  const containerRef = useRef(null);

  const first  = swapped ? slotB : slotA;
  const second = swapped ? slotA : slotB;

  const onDividerMouseDown = useCallback((e) => {
    e.preventDefault();
    dragging.current = true;
    document.body.style.cursor = orientation === 'vertical' ? 'col-resize' : 'row-resize';
    document.body.style.userSelect = 'none';

    const onMove = (ev) => {
      if (!dragging.current || !containerRef.current) return;
      const rect = containerRef.current.getBoundingClientRect();
      const pct = orientation === 'vertical'
        ? ((ev.clientX - rect.left) / rect.width) * 100
        : ((ev.clientY - rect.top)  / rect.height) * 100;
      setSplitPct(Math.min(80, Math.max(20, pct)));
    };

    const onUp = () => {
      dragging.current = false;
      document.body.style.cursor = '';
      document.body.style.userSelect = '';
      window.removeEventListener('mousemove', onMove);
      window.removeEventListener('mouseup', onUp);
    };

    window.addEventListener('mousemove', onMove);
    window.addEventListener('mouseup', onUp);
  }, [orientation]);

  const isVertical = orientation === 'vertical';

  return (
    <div
      ref={containerRef}
      className={`${styles.container} ${isVertical ? styles.vertical : styles.horizontal}`}
      data-testid="learn-layout"
    >
      <div
        className={styles.pane}
        style={isVertical ? { width: `${splitPct}%` } : { height: `${splitPct}%` }}
        data-testid="learn-pane-first"
      >
        {first}
      </div>

      <div
        className={`${styles.divider} ${isVertical ? styles.dividerV : styles.dividerH}`}
        onMouseDown={onDividerMouseDown}
        data-testid="learn-divider"
      >
        <button
          className={styles.swapBtn}
          onClick={() => setSwapped(s => !s)}
          title="Swap panels"
          data-testid="learn-swap"
        >
          {isVertical ? '⇄' : '⇅'}
        </button>
      </div>

      <div
        className={`${styles.pane} ${styles.paneSecond}`}
        data-testid="learn-pane-second"
      >
        {second}
      </div>
    </div>
  );
}
