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
let reloadOnControllerChange = false;

export async function registerServiceWorker() {
  if (!('serviceWorker' in navigator)) return null;
  // Secure-context guard: localhost is treated as secure even over http.
  if (!window.isSecureContext) {
    console.info('[PWA] not a secure context — service worker skipped');
    return null;
  }
  try {
    registration = await navigator.serviceWorker.register('/sw.js', { scope: '/' });
    requestPersistentStorage();

    // Auto-check for a new deploy: immediately on launch, then every 15 minutes. This is
    // what makes the "Update available" prompt appear on its own instead of only when the
    // user manually taps refresh. Purely best-effort — checkForUpdate() just nudges the
    // browser to re-fetch sw.js; it NEVER touches auth, so if the server is down the check
    // silently no-ops and you stay signed in (see useStore.checkAuth for the auth side).
    checkForUpdate();
    setInterval(checkForUpdate, 15 * 60 * 1000);

    // sw.js no longer self-activates (no skipWaiting() on install) — a new SW installs and
    // WAITS, so the tab keeps running on the old cache/old fetch logic until the user
    // explicitly clicks Update. This just watches for that waiting worker and notifies;
    // notifyIfWaiting() does the actual "does this represent a real update" check.
    if (registration.waiting) notifyIfWaiting(registration);
    registration.addEventListener('updatefound', () => {
      const installing = registration.installing;
      if (!installing) return;
      installing.addEventListener('statechange', () => {
        if (installing.state === 'installed') notifyIfWaiting(registration);
      });
    });

    navigator.serviceWorker.addEventListener('controllerchange', () => {
      if (reloadOnControllerChange) window.location.reload();
    });

    return registration;
  } catch (e) {
    console.warn('[PWA] service worker registration failed:', e);
    return null;
  }
}

// A worker sits in `registration.waiting` only once BOTH are true: install finished, and
// there's already a controller (i.e. this isn't the very first install on a blank tab —
// there's an old version actively running that the new one would replace).
function notifyIfWaiting(reg) {
  if (!reg.waiting || !navigator.serviceWorker.controller || notified) return;
  notified = true;
  updateListeners.forEach((cb) => cb());
}

// Subscribe to "a new version finished downloading and is waiting to take over — ask the
// user before applying it". Returns an unsubscribe function. Fires at most once per page
// load. Call applyUpdate() (not reloadApp()) to actually swap it in.
export function onUpdateAvailable(cb) {
  updateListeners.push(cb);
  return () => { updateListeners = updateListeners.filter((l) => l !== cb); };
}

// User clicked "Update": tell the waiting worker to activate, then reload once it does.
// This is the ONLY thing that hands control to the new SW — old cache stays intact and
// serving until this fires.
export function applyUpdate() {
  if (!registration?.waiting) { reloadApp(); return; }
  reloadOnControllerChange = true;
  registration.waiting.postMessage('SKIP_WAITING');
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
