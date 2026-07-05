import { useRef, useState, useCallback } from 'react';
import { useIsMobile } from '../../utils/useMediaQuery';
import styles from './LearnLayout.module.css';

// orientation: 'vertical' (left|right) | 'horizontal' (top|bottom)
// Default slot A = resource, slot B = note.
// swapped flips which slot renders in which position.
//
// Desktop: adjustable, swappable split (drag divider). Mobile: a phone can't
// usefully show two panes at once, so instead of a squished split we render ONE
// pane at a time with a segmented [labelA | labelB] toggle. Same slots, different
// display logic — driven by useIsMobile (jsdom has no matchMedia → desktop path
// under test, so the existing split tests stay valid).
export default function LearnLayout({
  orientation = 'vertical',
  slotA,
  slotB,
  labelA = 'Source',
  labelB = 'Note',
}) {
  const [swapped, setSwapped]     = useState(false);
  const [splitPct, setSplitPct]   = useState(50);
  const [activeSlot, setActive]   = useState('A');   // mobile single-view selection
  const dragging = useRef(false);
  const containerRef = useRef(null);
  const isMobile = useIsMobile();

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

  // ── Mobile: single pane + segmented toggle ────────────────────────────────
  if (isMobile) {
    return (
      <div className={`${styles.container} ${styles.mobile}`} data-testid="learn-layout">
        <div className={styles.segmented} role="tablist" aria-label="View">
          <button
            role="tab"
            aria-selected={activeSlot === 'A'}
            className={`${styles.seg} ${activeSlot === 'A' ? styles.segOn : ''}`}
            onClick={() => setActive('A')}
          >
            {labelA}
          </button>
          <button
            role="tab"
            aria-selected={activeSlot === 'B'}
            className={`${styles.seg} ${activeSlot === 'B' ? styles.segOn : ''}`}
            onClick={() => setActive('B')}
          >
            {labelB}
          </button>
        </div>
        <div className={`${styles.pane} ${styles.paneSecond}`} data-testid="learn-pane-active">
          {activeSlot === 'A' ? slotA : slotB}
        </div>
      </div>
    );
  }

  // ── Desktop: adjustable split ─────────────────────────────────────────────
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
