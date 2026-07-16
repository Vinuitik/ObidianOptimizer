// Captures the browser's `beforeinstallprompt` so a real in-app "Install" button can trigger
// the native PWA install (Chrome/Edge fire it once, early, and you must stash the event to
// call .prompt() later on a user gesture). Firefox/Safari never fire it → the button falls
// back to manual guidance. Imported for side-effects from main.jsx so we don't miss the event.
let deferred = null;
const listeners = new Set();

function emit() { for (const fn of listeners) { try { fn(state()); } catch {} } }

function isStandalone() {
  return (typeof window !== 'undefined')
    && (window.matchMedia?.('(display-mode: standalone)').matches || window.navigator.standalone === true);
}

export function state() {
  return { canInstall: !!deferred, installed: isStandalone() };
}

// Subscribe to install-availability changes; returns an unsubscribe. Fires once immediately.
export function onInstallChange(fn) {
  listeners.add(fn);
  fn(state());
  return () => listeners.delete(fn);
}

// Trigger the native prompt. Returns 'accepted' | 'dismissed' | 'unavailable'.
export async function promptInstall() {
  if (!deferred) return 'unavailable';
  const e = deferred;
  deferred = null;          // a captured prompt is single-use
  emit();
  e.prompt();
  const { outcome } = await e.userChoice.catch(() => ({ outcome: 'dismissed' }));
  return outcome;
}

if (typeof window !== 'undefined') {
  window.addEventListener('beforeinstallprompt', (e) => {
    e.preventDefault();     // stop the mini-infobar; we drive install from the Get App view
    deferred = e;
    emit();
  });
  window.addEventListener('appinstalled', () => { deferred = null; emit(); });
}
