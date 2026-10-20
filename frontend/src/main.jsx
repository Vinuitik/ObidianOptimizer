import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import './styles/globals.css';
import 'katex/dist/katex.min.css';
import ResponsiveApp from './pwa/ResponsiveApp';
import { registerServiceWorker, onUpdateAvailable } from './pwa/registerSW';
import useStore from './store/useStore';
import { installVaultFetch, bootVault } from './vault/activeVault';
import './pwa/installPrompt';   // side-effect: capture beforeinstallprompt early for the Get App view

// ResponsiveApp renders the full site in a browser and the narrow PWA when launched
// from the installed home-screen icon (display-mode: standalone). Desktop browsers
// are unaffected (they get App, unchanged). SW registration no-ops outside a secure
// context, so it only activates over the real-cert tunnel domain.
registerServiceWorker();

// Vault scoping (VAULTS_PLAN M7): every /api call carries X-Vault-Id; adopt the server's active
// vault before first render (offline => keep the stored id). A changed id reloads once so
// nothing built from the stale id (IDB handle, media URLs) survives.
installVaultFetch();

// When the auto-check finds a new deploy, nudge the user with a toast (the RefreshButton's
// persistent "Update" label lights up too, so they can act whenever). Reload gets the new code.
// The installed PWA shows its own persistent "Update ready" banner instead (pwa/MobileLayout).
onUpdateAvailable(() => {
  const standalone = window.matchMedia?.('(display-mode: standalone)').matches
    || new URLSearchParams(location.search).has('pwa');
  if (!standalone) useStore.getState().showToast('New version available — tap the refresh button to update.');
});

bootVault().then(({ changed }) => {
  if (changed) { location.reload(); return; }
  createRoot(document.getElementById('root')).render(
    <StrictMode>
      <ResponsiveApp />
    </StrictMode>
  );
});
