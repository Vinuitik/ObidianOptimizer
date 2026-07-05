import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import './styles/globals.css';
import 'katex/dist/katex.min.css';
import ResponsiveApp from './pwa/ResponsiveApp';
import { registerServiceWorker } from './pwa/registerSW';

// ResponsiveApp renders the full site in a browser and the narrow PWA when launched
// from the installed home-screen icon (display-mode: standalone). Desktop browsers
// are unaffected (they get App, unchanged). SW registration no-ops outside a secure
// context, so it only activates over the real-cert tunnel domain.
registerServiceWorker();

createRoot(document.getElementById('root')).render(
  <StrictMode>
    <ResponsiveApp />
  </StrictMode>
);
