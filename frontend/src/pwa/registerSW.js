// PWA service-worker registration + update detection + storage persistence.
//
// Wire-up (one line in src/main.jsx, kept out of this folder so the desktop build
// is untouched until you opt in):
//
//   import { registerServiceWorker } from './pwa/registerSW';
//   registerServiceWorker();
//
// Registration silently no-ops outside a secure context (self-signed :8443 will
// refuse it). Install + first sync must happen over the Cloudflare tunnel domain,
// which serves a real cert. See architecture_plans/PWA_MOBILE_ARCH.md §9.

let registration = null;
let updateListeners = [];
let notified = false;

export async function registerServiceWorker() {
  if (!('serviceWorker' in navigator)) return null;
  // Secure-context guard: localhost is treated as secure even over http.
  if (!window.isSecureContext) {
    console.info('[PWA] not a secure context — service worker skipped');
    return null;
  }
  try {
    // A controller already present at load time means an OLDER SW was running this
    // page — the next controllerchange is therefore a real update, not the very
    // first install (which has no prior controller to change FROM).
    const hadController = !!navigator.serviceWorker.controller;

    registration = await navigator.serviceWorker.register('/sw.js', { scope: '/' });
    requestPersistentStorage();

    // Auto-check for a new deploy: immediately on launch, then every 15 minutes. This is
    // what makes the "Update available" prompt appear on its own instead of only when the
    // user manually taps refresh. Purely best-effort — checkForUpdate() just nudges the
    // browser to re-fetch sw.js; it NEVER touches auth, so if the server is down the check
    // silently no-ops and you stay signed in (see useStore.checkAuth for the auth side).
    checkForUpdate();
    setInterval(checkForUpdate, 15 * 60 * 1000);

    navigator.serviceWorker.addEventListener('controllerchange', () => {
      if (!hadController || notified) return;
      notified = true;
      updateListeners.forEach((cb) => cb());
    });

    return registration;
  } catch (e) {
    console.warn('[PWA] service worker registration failed:', e);
    return null;
  }
}

// Subscribe to "a new version has taken over — reload to see it". Returns an
// unsubscribe function. Fires at most once per page load — sw.js self-activates
// via skipWaiting()/clients.claim(), so by the time this fires the new SW is
// already serving requests; reload just swaps the loaded JS bundle over.
export function onUpdateAvailable(cb) {
  updateListeners.push(cb);
  return () => { updateListeners = updateListeners.filter((l) => l !== cb); };
}

// Manual "check now" — nudges the browser to re-fetch sw.js instead of waiting on
// its own multi-hour cache-lifetime check.
export async function checkForUpdate() {
  try {
    const reg = registration || (await navigator.serviceWorker.getRegistration());
    await reg?.update();
  } catch {
    /* best-effort */
  }
}

export function reloadApp() {
  window.location.reload();
}

// Ask Android not to evict our offline review set under storage pressure.
export async function requestPersistentStorage() {
  try {
    if (navigator.storage?.persist) {
      const already = await navigator.storage.persisted();
      if (!already) await navigator.storage.persist();
    }
  } catch {
    /* best-effort */
  }
}
