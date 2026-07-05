import { useEffect, useState } from 'react';

// Canonical runtime viewport hook (shared by desktop + PWA). Guards SSR / jsdom
// (no window.matchMedia → returns false, so components render their desktop
// branch under test). To change a breakpoint, change the query the caller passes.
export default function useMediaQuery(query) {
  const get = () => (typeof window !== 'undefined' && window.matchMedia
    ? window.matchMedia(query).matches
    : false);

  const [matches, setMatches] = useState(get);

  useEffect(() => {
    if (typeof window === 'undefined' || !window.matchMedia) return;
    const mql = window.matchMedia(query);
    const onChange = () => setMatches(mql.matches);
    onChange();
    mql.addEventListener('change', onChange);
    return () => mql.removeEventListener('change', onChange);
  }, [query]);

  return matches;
}

// Convenience: the app-wide phone breakpoint, matching the CSS @media rules.
export const MOBILE_QUERY = '(max-width: 768px)';
export const useIsMobile = () => useMediaQuery(MOBILE_QUERY);
