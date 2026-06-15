import App from '../App';
import MobileApp from './MobileApp';
import useMediaQuery from './useMediaQuery';

// The single viewport switch. Desktop renders the EXISTING App untouched; phones
// render the mobile shell. Both drive the same Zustand store and reuse the same
// leaf components — a fix in FlashcardSession/ReviewPage lands on both surfaces.
//
// To activate, swap the root in src/main.jsx:
//   import ResponsiveApp from './pwa/ResponsiveApp';
//   import { registerServiceWorker } from './pwa/registerSW';
//   registerServiceWorker();
//   createRoot(...).render(<StrictMode><ResponsiveApp /></StrictMode>);
//
// To change the breakpoint, edit the query below.
export default function ResponsiveApp() {
  const isMobile = useMediaQuery('(max-width: 768px)');
  return isMobile ? <MobileApp /> : <App />;
}
