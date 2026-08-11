// Captures the browser's `beforeinstallprompt` so a real in-app "Install" button can trigger
// the native PWA install (Chrome/Edge fire it once, early, and you must stash the event to
// call .prompt() later on a user gesture). Firefox/Safari never fire it → the button falls
// back to manual guidance. Imported for side-effects from main.jsx so we don't miss the event.
let deferred = null;
const listeners = new Set();

function emit() { state().then(s => { for (const fn of listeners) { try { fn(s); } catch {} } }); }

function isStandalone() {
  return (typeof window !== 'undefined')
    && (window.matchMedia?.('(display-mode: standalone)').matches || window.navigator.standalone === true);
}

// Platform the "Get App" page decides its single CTA from. Windows always gets the
// Electron download (richer than the PWA install — real OS close-guard); iOS never gets
// a real install button (no beforeinstallprompt API exists there, ever).
export function isIOS() {
  return typeof navigator !== 'undefined' && /iPad|iPhone|iPod/.test(navigator.userAgent);
}

export function isWindows() {
  return typeof navigator !== 'undefined' && /Windows/.test(navigator.userAgent);
}

// True only when this page is currently being viewed INSIDE the installed Electron shell
// (desktop/main.js never strips "Electron/..." from the UA). Same limitation as
// isStandalone() above: tells us "installed AND you're in it right now", not "installed,
// but you're checking from a regular browser tab" — no API exists for that, the .exe isn't
// Store-listed so getInstalledRelatedApps() can't see it either.
export function isElectron() {
  return typeof navigator !== 'undefined' && /Electron/.test(navigator.userAgent);
}

// Best-effort "already installed, but we're browsing in a regular tab right now" check.
// Only Chrome/Edge support this (requires a self-referencing `related_applications` entry
// in manifest.webmanifest); everywhere else it resolves false and we fall back to the
// isStandalone()/canInstall states, same as before this existed.
async function isInstalledElsewhere() {
  try {
    const related = await navigator.getInstalledRelatedApps?.();
    return !!related?.length;
  } catch {
    return false;
  }
}

export async function state() {
  return {
    canInstall: !!deferred,
    installed: isStandalone() || isElectron() || await isInstalledElsewhere(),
  };
}

// Subscribe to install-availability changes; returns an unsubscribe. Fires once immediately.
export function onInstallChange(fn) {
  listeners.add(fn);
  state().then(fn);
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
