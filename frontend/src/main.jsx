import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import './styles/globals.css';
import 'katex/dist/katex.min.css';
import ResponsiveApp from './pwa/ResponsiveApp';
import { registerServiceWorker, onUpdateAvailable } from './pwa/registerSW';
import useStore from './store/useStore';
import './pwa/installPrompt';   // side-effect: capture beforeinstallprompt early for the Get App view

// ResponsiveApp renders the full site in a browser and the narrow PWA when launched
// from the installed home-screen icon (display-mode: standalone). Desktop browsers
// are unaffected (they get App, unchanged). SW registration no-ops outside a secure
// context, so it only activates over the real-cert tunnel domain.
registerServiceWorker();

// When the auto-check finds a new deploy, nudge the user with a toast (the RefreshButton's
// persistent "Update" label lights up too, so they can act whenever). Reload gets the new code.
onUpdateAvailable(() =>
  useStore.getState().showToast('New version available — tap the refresh button to update.')
);

createRoot(document.getElementById('root')).render(
  <StrictMode>
    <ResponsiveApp />
  </StrictMode>
);
