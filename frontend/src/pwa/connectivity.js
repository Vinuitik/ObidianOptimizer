// Connectivity signal. navigator.onLine is the cheap baseline; the 'online' /
// 'offline' events let the app react (e.g. flush the grade outbox on reconnect).
//
// navigator.onLine only knows whether the device has *a* network — not whether the
// server is reachable. Treat it as a hint; the offline data layer still falls back
// to IndexedDB whenever a fetch actually throws.

export function isOnline() {
  return typeof navigator === 'undefined' ? true : navigator.onLine;
}

export function onConnectivityChange(handler) {
  const online = () => handler(true);
  const offline = () => handler(false);
  window.addEventListener('online', online);
  window.addEventListener('offline', offline);
  return () => {
    window.removeEventListener('online', online);
    window.removeEventListener('offline', offline);
  };
}
