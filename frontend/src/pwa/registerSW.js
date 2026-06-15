// PWA service-worker registration + storage persistence.
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

export async function registerServiceWorker() {
  if (!('serviceWorker' in navigator)) return null;
  // Secure-context guard: localhost is treated as secure even over http.
  if (!window.isSecureContext) {
    console.info('[PWA] not a secure context — service worker skipped');
    return null;
  }
  try {
    const reg = await navigator.serviceWorker.register('/sw.js', { scope: '/' });
    requestPersistentStorage();
    return reg;
  } catch (e) {
    console.warn('[PWA] service worker registration failed:', e);
    return null;
  }
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
