import App from '../App';
import PwaApp from './MobileApp';
import useMediaQuery from './useMediaQuery';
import { isElectron } from './installPrompt';

// The layout seam. Two SEPARATE experiences, chosen by HOW the app was opened —
// not by screen width:
//   • Installed to the home screen (display-mode: standalone) → the narrow, offline
//     PWA (flashcards · learn · capture). This is "the app".
//   • Opened as a link in a browser (phone OR desktop) → the full responsive site
//     (App), which reflows on small screens (drawers, single-view Learn, etc.).
// So on a phone you get the full site via a link AND the focused app via the icon,
// from ONE codebase / one deploy.
//
// Override for testing (you can't reinstall to flip modes): ?pwa=1 forces the app
// shell, ?pwa=0 forces the full site — on any device.
export default function ResponsiveApp() {
  const standalone = useMediaQuery('(display-mode: standalone)');
  const iosStandalone = typeof navigator !== 'undefined' && navigator.standalone === true;

  let usePwa = standalone || iosStandalone;
  // The Electron desktop shell reports display-mode: standalone like an installed PWA, but
  // it's a WIDE window — the narrow phone shell renders squished with empty space there.
  // Force the full desktop site (sidebar, editor, tree), which is the right fit for a desktop
  // window. ?pwa=1 below still overrides for testing.
  if (isElectron()) usePwa = false;
  if (typeof window !== 'undefined') {
    const forced = new URLSearchParams(window.location.search).get('pwa');
    if (forced === '1') usePwa = true;
    if (forced === '0') usePwa = false;
  }

  return usePwa ? <PwaApp /> : <App />;
}
