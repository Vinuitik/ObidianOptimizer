// Shared "a queued capture left the device" notification — fired by BOTH drain paths
// (outbox.js flush() for server-direct, mailbox.js pushMailbox() for the Drive fallback)
// so confirmation looks the same regardless of which one actually got it out. Its own
// mutable preference (SyncPage.jsx Notifications section), independent of the OS
// Notification permission gate showLocalNotification already checks — someone may want
// the quit-nag but not this, or vice versa.
import { getMeta } from './db';
import { showLocalNotification } from './quitNotify';

const PREF_KEY = 'notifyCaptureSent';

export async function captureSentNotifyEnabled() {
  return (await getMeta(PREF_KEY)) !== false;
}

function captureLabel(item) {
  if (item.kind === 'capture') return item.url;
  if (item.kind === 'captureText') return item.title || (item.text || '').slice(0, 60) || 'Note';
  return item.filename || 'Shared file';
}

// `destination` names WHERE it actually landed — "on the server, processing into a note"
// (server-direct) vs. "to Drive — will process once the server drains it" (mailbox) — so
// the notification itself carries the same honesty the in-app status text does.
export function notifyCaptureSent(item, destination) {
  showLocalNotification('Capture sent', {
    body: `${captureLabel(item)} left the queue — ${destination}.`,
    tag: `obsopt-capture-sent-${item.id ?? item.eventId}`,
  });
}
