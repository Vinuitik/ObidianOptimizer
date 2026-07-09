// Grade + capture outbox. Writes made while offline (or while the session is
// expired) are queued here and replayed on reconnect / re-login.
import { addToOutbox, getOutbox, deleteFromOutbox } from './db';
import { gradeNote } from '../api/notes';

// eventId makes mailbox replay idempotent — the server dedupes on it (consumed_events).
const newId = () => (crypto.randomUUID ? crypto.randomUUID() : `${Date.now()}-${Math.random()}`);

export function enqueueGrade(notePath, band) {
  return addToOutbox({ kind: 'grade', notePath, band, eventId: newId() });
}

export function enqueueCapture(url) {
  return addToOutbox({ kind: 'capture', url, eventId: newId() });
}

// A typed raw note (brain dump) → text ingest → Learn inbox. Replayed server-direct like
// `capture` — ingestion needs the server/embedder anyway, so no Drive mailbox is warranted.
export function enqueueCaptureText(text, title) {
  return addToOutbox({ kind: 'captureText', text, title, eventId: newId() });
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

// Replay everything. Returns { sent, failed }. A 401 leaves items queued (the
// caller should prompt login via the existing LoginModal, then flush again).
export async function flush() {
  const items = await getOutbox();
  let sent = 0, failed = 0;
  for (const item of items) {
    try {
      if (item.kind === 'grade') {
        await gradeNote(item.notePath, item.band);
      } else if (item.kind === 'capture') {
        const res = await fetch('/api/capture', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          credentials: 'same-origin',
          body: JSON.stringify({ url: item.url }),
        });
        if (!res.ok) throw new Error('capture ' + res.status);
      } else if (item.kind === 'captureText') {
        const res = await fetch('/api/capture', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          credentials: 'same-origin',
          body: JSON.stringify({ text: item.text, title: item.title }),
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
    } catch (e) {
      failed++; // leave it queued for the next flush
    }
  }
  return { sent, failed };
}

export async function pendingCount() {
  return (await getOutbox()).length;
}
