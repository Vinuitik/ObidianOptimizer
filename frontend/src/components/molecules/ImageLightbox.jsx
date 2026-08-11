import { useCallback, useEffect, useRef, useState } from 'react';
import styles from './ImageLightbox.module.css';

// Fullscreen image zoom overlay. Installed standalone PWAs disable the browser's own
// pinch-to-zoom, so embedded images (capped at max-width:100%) can never be enlarged.
// This gives back real zoom, hand-rolled with pointer events so it works identically on
// iOS/Android standalone, mobile browser, and desktop:
//   • pinch (two fingers) → zoom, anchored on the pinch midpoint
//   • drag (one finger / mouse) → pan when zoomed in
//   • double-tap / double-click → toggle 1× ↔ 2.5×, centered on the tap
//   • wheel → zoom (desktop)
//   • tap backdrop while at 1× / ✕ button / Esc → close
//
// The live transform is held in a ref (`tf`) as the single source of truth so gesture
// math never reads stale state; `view` state exists only to re-render the <img>.
const MIN = 1, MAX = 6;
const clamp = (v, lo, hi) => Math.min(hi, Math.max(lo, v));

export default function ImageLightbox({ src, alt = '', onClose }) {
  const [view, setView] = useState({ scale: 1, tx: 0, ty: 0 });
  const tf       = useRef({ scale: 1, tx: 0, ty: 0 });
  const wrapRef  = useRef(null);
  const pointers = useRef(new Map());          // pointerId → {x,y}
  const pinch    = useRef(null);               // { dist }
  const pan      = useRef(null);               // { x, y, downX, downY }
  const lastTap  = useRef(0);

  // Commit a transform: clamp scale + keep pan within bounds, mirror to ref and state.
  const commit = useCallback((scale, tx, ty) => {
    const s = clamp(scale, MIN, MAX);
    const c = wrapRef.current?.getBoundingClientRect();
    const w = c?.width ?? window.innerWidth, h = c?.height ?? window.innerHeight;
    const mx = (s - 1) * w / 2, my = (s - 1) * h / 2;
    const next = { scale: s, tx: clamp(tx, -mx, mx), ty: clamp(ty, -my, my) };
    tf.current = next;
    setView(next);
  }, []);

  const reset = useCallback(() => commit(1, 0, 0), [commit]);

  // Zoom toward a screen point (px,py), keeping the content under it fixed.
  const zoomTo = useCallback((nextScale, px, py) => {
    const r = wrapRef.current?.getBoundingClientRect(); if (!r) return;
    const { scale: s0, tx: tx0, ty: ty0 } = tf.current;
    const s = clamp(nextScale, MIN, MAX), f = s / s0;
    const rx = px - (r.left + r.width / 2), ry = py - (r.top + r.height / 2);
    commit(s, rx - f * (rx - tx0), ry - f * (ry - ty0));
  }, [commit]);

  const onPointerDown = useCallback((e) => {
    e.currentTarget.setPointerCapture?.(e.pointerId);
    pointers.current.set(e.pointerId, { x: e.clientX, y: e.clientY });
    const pts = [...pointers.current.values()];
    if (pts.length === 2) {
      pinch.current = { dist: Math.hypot(pts[0].x - pts[1].x, pts[0].y - pts[1].y) };
      pan.current = null;
    } else {
      pan.current = { x: e.clientX, y: e.clientY, downX: e.clientX, downY: e.clientY };
    }
  }, []);

  const onPointerMove = useCallback((e) => {
    if (!pointers.current.has(e.pointerId)) return;
    pointers.current.set(e.pointerId, { x: e.clientX, y: e.clientY });
    const pts = [...pointers.current.values()];
    if (pts.length === 2 && pinch.current) {
      const dist = Math.hypot(pts[0].x - pts[1].x, pts[0].y - pts[1].y);
      zoomTo(tf.current.scale * (dist / pinch.current.dist),
             (pts[0].x + pts[1].x) / 2, (pts[0].y + pts[1].y) / 2);
      pinch.current.dist = dist;
    } else if (pts.length === 1 && pan.current && tf.current.scale > 1) {
      const dx = e.clientX - pan.current.x, dy = e.clientY - pan.current.y;
      pan.current.x = e.clientX; pan.current.y = e.clientY;
      commit(tf.current.scale, tf.current.tx + dx, tf.current.ty + dy);
    }
  }, [zoomTo, commit]);

  const onPointerUp = useCallback((e) => {
    const p = pan.current;
    pointers.current.delete(e.pointerId);
    if (pointers.current.size < 2) pinch.current = null;
    if (pointers.current.size === 0) pan.current = null;

    // Tap = pointerup with no meaningful drag.
    if (p && (Math.abs(e.clientX - p.downX) > 6 || Math.abs(e.clientY - p.downY) > 6)) return;
    const now = Date.now();
    if (now - lastTap.current < 300) {           // double-tap → toggle zoom
      lastTap.current = 0;
      if (tf.current.scale > 1) reset(); else zoomTo(2.5, e.clientX, e.clientY);
    } else {
      lastTap.current = now;
      // single tap on the backdrop while at 1× → close (deferred so a 2nd tap can win)
      if (tf.current.scale === 1 && e.target === wrapRef.current) {
        setTimeout(() => { if (lastTap.current === now) onClose?.(); }, 300);
      }
    }
  }, [reset, zoomTo, onClose]);

  const onWheel = useCallback((e) => {
    e.preventDefault();
    zoomTo(tf.current.scale * (e.deltaY < 0 ? 1.15 : 0.87), e.clientX, e.clientY);
  }, [zoomTo]);

  useEffect(() => {
    const onKey = (e) => { if (e.key === 'Escape') onClose?.(); };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);

  return (
    <div className={styles.overlay} role="dialog" aria-modal="true">
      <button className={styles.close} onClick={onClose} aria-label="Close">✕</button>
      <div
        ref={wrapRef}
        className={styles.stage}
        onPointerDown={onPointerDown}
        onPointerMove={onPointerMove}
        onPointerUp={onPointerUp}
        onPointerCancel={onPointerUp}
        onWheel={onWheel}
      >
        <img
          className={styles.img}
          src={src}
          alt={alt}
          draggable={false}
          style={{ transform: `translate(${view.tx}px, ${view.ty}px) scale(${view.scale})` }}
        />
      </div>
    </div>
  );
}
