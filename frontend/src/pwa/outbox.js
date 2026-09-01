// Grade + capture outbox. Writes made while offline (or while the session is
// expired) are queued here and replayed on reconnect / re-login.
import { addToOutbox, getOutbox, deleteFromOutbox } from './db';
import { gradeNote } from '../api/notes';
import { captureSentNotifyEnabled, notifyCaptureSent } from './captureSentNotify';

// eventId makes mailbox replay idempotent — the server dedupes on it (consumed_events).
const newId = () => (crypto.randomUUID ? crypto.randomUUID() : `${Date.now()}-${Math.random()}`);

const CAPTURE_KINDS = new Set(['capture', 'captureText', 'captureFile']);

export function enqueueGrade(notePath, band) {
  return addToOutbox({ kind: 'grade', notePath, band, eventId: newId() });
}

// trackOpts (tracks/FLOWS.md Phase 1b): { trackId } or { newTrackTitle, newTrackType? } —
// carried through the queue so an offline capture still lands on the right track once
// flush() replays it. {} ⇒ today's exact untagged behavior.
export function enqueueCapture(url, trackOpts = {}) {
  return addToOutbox({ kind: 'capture', url, trackOpts, eventId: newId() });
}

// A typed raw note (brain dump) → text ingest → Learn inbox. Same durability path as
// `capture`: server-direct when reachable, Drive mailbox as plan B when online-but-
// server-down, local IndexedDB as the last resort when truly offline (mailbox.js).
export function enqueueCaptureText(text, title, trackOpts = {}) {
  return addToOutbox({ kind: 'captureText', text, title, trackOpts, eventId: newId() });
}

export function enqueueAssignment(assignmentId, notePath, answers) {
  return addToOutbox({ kind: 'assignment', assignmentId, notePath, answers, eventId: newId() });
}

export function enqueueFile(path, targetFolder, content) {
  return addToOutbox({ kind: 'file', path, targetFolder, content, eventId: newId() });
}

export function enqueueDiscard(path) {
  return addToOutbox({ kind: 'discard', path, eventId: newId() });
}

export function enqueueAcknowledge(captureId) {
  return addToOutbox({ kind: 'acknowledge', captureId, eventId: newId() });
}

export function enqueueFlag(cardId, reason) {
  return addToOutbox({ kind: 'flag', cardId, reason, eventId: newId() });
}

// Replay everything. Returns { sent, failed }. A 401 leaves items queued (the
// caller should prompt login via the existing LoginModal, then flush again).
export async function flush() {
  const items = await getOutbox();
  let sent = 0, failed = 0;
  const notifyOn = await captureSentNotifyEnabled();
  for (const item of items) {
    try {
      if (item.kind === 'grade') {
        await gradeNote(item.notePath, item.band);
      } else if (item.kind === 'capture') {
        const res = await fetch('/api/capture', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          credentials: 'same-origin',
          body: JSON.stringify({ url: item.url, ...item.trackOpts }),
        });
        if (!res.ok) throw new Error('capture ' + res.status);
      } else if (item.kind === 'captureText') {
        const res = await fetch('/api/capture', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          credentials: 'same-origin',
          body: JSON.stringify({ text: item.text, title: item.title, ...item.trackOpts }),
        });
        if (!res.ok) throw new Error('captureText ' + res.status);
      } else if (item.kind === 'captureFile') {
        // Queued by the service worker when a file was shared offline (public/sw.js).
        const fd = new FormData();
        fd.append('file', item.blob, item.filename || 'shared');
        if (item.title) fd.append('title', item.title);
        const res = await fetch('/api/capture/file', {
          method: 'POST', credentials: 'same-origin', body: fd,
        });
        if (!res.ok) throw new Error('captureFile ' + res.status);
      }
      await deleteFromOutbox(item.id);
      sent++;
      if (notifyOn && CAPTURE_KINDS.has(item.kind)) {
        notifyCaptureSent(item, 'now on the server, processing into a note');
      }
    } catch (e) {
      failed++; // leave it queued for the next flush
    }
  }
  return { sent, failed };
}

export async function pendingCount() {
  return (await getOutbox()).length;
}
