import { motion, useReducedMotion } from 'framer-motion';
import { fadeRise, fadeRiseReduced } from './presets';

// Enter-only page wrapper (no AnimatePresence / exit wait). Remount it (via a `key`
// or by being a route element) to replay the animation. Reduced-motion → opacity only.
export default function PageTransition({ children, className }) {
  const reduce = useReducedMotion();
  const v = reduce ? fadeRiseReduced : fadeRise;
  return (
    <motion.div className={className} variants={v} initial="initial" animate="animate">
      {children}
    </motion.div>
  );
}
