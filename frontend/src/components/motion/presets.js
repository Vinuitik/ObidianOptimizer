// framer-motion variants. Durations/easings mirror the CSS motion tokens in styles/tokens.css
// (dur-2 200ms, dur-3 320ms, ease-out cubic-bezier(.2,.8,.2,1)). Reduced-motion variants
// (…Reduced) are opacity-only; PageTransition / pickers choose via useReducedMotion().

export const EASE_OUT = [0.2, 0.8, 0.2, 1];
export const DUR = { fast: 0.12, base: 0.2, page: 0.32 };

// Page enter: fade + 4px rise. Enter-only (no exit wait).
export const fadeRise = {
  initial: { opacity: 0, y: 4 },
  animate: { opacity: 1, y: 0, transition: { duration: DUR.page, ease: EASE_OUT } },
};
export const fadeRiseReduced = {
  initial: { opacity: 0 },
  animate: { opacity: 1, transition: { duration: DUR.base } },
};

// List rows: enter fade-rise, exit collapses height + opacity so the next item moves up.
export const listItem = {
  initial: { opacity: 0, y: 4 },
  animate: { opacity: 1, y: 0, transition: { duration: DUR.base, ease: EASE_OUT } },
  exit: { opacity: 0, height: 0, overflow: 'hidden', transition: { duration: DUR.base, ease: EASE_OUT } },
};

// Disclosure / accordion body.
export const collapse = {
  initial: { height: 0, opacity: 0 },
  animate: { height: 'auto', opacity: 1, transition: { duration: DUR.base, ease: EASE_OUT } },
  exit: { height: 0, opacity: 0, transition: { duration: DUR.base, ease: EASE_OUT } },
};

// Swipe cards: `custom` = direction (-1 left, 1 right); next card scales up.
export const swipe = {
  initial: { opacity: 0, scale: 0.96 },
  animate: { opacity: 1, scale: 1, x: 0, rotate: 0, transition: { duration: DUR.base, ease: EASE_OUT } },
  exit: (dir = 1) => ({
    opacity: 0,
    x: dir * 280,
    rotate: dir * 8,
    transition: { duration: DUR.base, ease: EASE_OUT },
  }),
};
