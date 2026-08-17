// Mobile equivalent of QuitGuard.jsx's exit-intent nag. There's no mouseleave on a phone
// and no way to intercept "switching apps" — the only hook is visibilitychange going
// hidden, and by the time it fires the page is about to stop rendering, so a modal is
// useless. Instead we fire an OS notification via the service worker (works even fully
// backgrounded), same "caught in the act of leaving" philosophy as web/desktop, same
// no-nag-on-return rule. Opt-in only — Notification.requestPermission() needs a gesture
// and shouldn't be sprung on someone silently (see permission toggle in SyncPage.jsx).
import { dutyUnfinished, canNag, markNagged } from '../utils/dailyDuty';
import { randomQuote } from '../utils/quotes';

export function notificationsSupported() {
  return typeof window !== 'undefined' && 'Notification' in window && 'serviceWorker' in navigator;
}

export function notificationPermission() {
  return notificationsSupported() ? Notification.permission : 'unsupported';
}

// Must be called from a user gesture (a click handler) — browsers reject/ignore
// requestPermission() calls that aren't.
export async function requestNotificationPermission() {
  if (!notificationsSupported()) return 'unsupported';
  return Notification.requestPermission();
}

// Call once from the mobile shell (MobileLayout). No-op while signed out, permission
// not granted, or unsupported (iOS PWA notifications need 16.4+ AND home-screen install
// — there's no fallback, this silently does nothing on older iOS).
export function armQuitNotify(isAuthenticated) {
  if (!isAuthenticated || !notificationsSupported() || Notification.permission !== 'granted') {
    return () => {};
  }

  let unfinished = false;
  let cancelled = false;
  const refresh = () => dutyUnfinished().then(v => { if (!cancelled) unfinished = v; });
  refresh();

  const onVisible = async () => {
    if (document.visibilityState === 'visible') { refresh(); return; }
    // → hidden: the moment of backgrounding.
    if (!unfinished || !canNag()) return;
    markNagged();
    const q = randomQuote();
    const reg = await navigator.serviceWorker.ready;
    reg.showNotification('Are you a quitter?', {
      body: `${q.text} — ${q.author}`,
      tag: 'obsopt-quit-guard',   // replaces any previous nag instead of stacking
    });
  };
  document.addEventListener('visibilitychange', onVisible);
  return () => { cancelled = true; document.removeEventListener('visibilitychange', onVisible); };
}
