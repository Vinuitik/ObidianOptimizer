// Offline write outbox (grade, assignment, inbox file/discard/acknowledge, flag, capture).
// Writes made while offline (or while the session is expired) are queued here and
// replayed on reconnect / re-login.
import { addToOutbox, getOutbox, deleteFromOutbox } from './db';
import { gradeNote, submitAttempt, completeAssignment, flagCard } from '../api/notes';
import { fileInboxNote, discardInboxNote, acknowledgeCapture } from '../api/inbox';
import { acceptFeedItem, binFeedItem, restoreFeedItem } from '../api/feed';
import { getActiveVaultId } from '../vault/activeVault';
import { captureSentNotifyEnabled, notifyCaptureSent } from './captureSentNotify';

// eventId makes mailbox replay idempotent — the server dedupes on it (consumed_events).
const newId = () => (crypto.randomUUID ? crypto.randomUUID() : `${Date.now()}-${Math.random()}`);

const CAPTURE_KINDS = new Set(['capture', 'captureText', 'captureFile']);

export function enqueueGrade(notePath, band) {
  return addToOutbox({ kind: 'grade', notePath, band, eventId: newId() });
}

// Captures carry no track info (raw intake is Sources-only). Items queued by older builds may
// still hold a `trackOpts` field; replay ignores it.
export function enqueueCapture(url) {
  return addToOutbox({ kind: 'capture', url, eventId: newId() });
}

// A typed raw note (brain dump) → text ingest → Learn inbox. Same durability path as
// `capture`: server-direct when reachable, Drive mailbox as plan B when online-but-
// server-down, local IndexedDB as the last resort when truly offline (mailbox.js).
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

export function enqueueFlag(cardId, reason) {
  return addToOutbox({ kind: 'flag', cardId, reason, eventId: newId() });
}

// Passive Feed offline swipe decisions (feed/FLOWS_feed.md P2) — replayed through the SAME
// FeedService.accept/bin/restore the online buttons call, server-side (MailboxConsumeService
// for the Drive-mailbox path below; flush() here for the server-direct outbox path).
export function enqueueFeedAccept(itemId) {
  return addToOutbox({ kind: 'feed_accept', itemId, eventId: newId() });
}

export function enqueueFeedBin(itemId) {
  return addToOutbox({ kind: 'feed_bin', itemId, eventId: newId() });
}

export function enqueueFeedRestore(itemId) {
  return addToOutbox({ kind: 'feed_restore', itemId, eventId: newId() });
}

// Replay everything. Returns { sent, failed }. A 401 leaves items queued (the
// caller should prompt login via the existing LoginModal, then flush again).
export async function flush() {
  const items = await getOutbox();
  let sent = 0, failed = 0;
  const notifyOn = await captureSentNotifyEnabled();
  const activeId = getActiveVaultId();
  for (const item of items) {
    // Per-vault outbox: an entry queued under another vault never replays into this one.
    if (item.vaultId != null && item.vaultId !== activeId) continue;
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
      } else if (item.kind === 'assignment') {
        // Mirrors MailboxConsumeService.applyAssignment: replay each recorded answer as a
        // real attempt, then complete — same as an interactive session would have done.
        for (const [cardId, answer] of Object.entries(item.answers || {})) {
          await submitAttempt(item.assignmentId, cardId, answer);
        }
        await completeAssignment(item.assignmentId);
      } else if (item.kind === 'file') {
        await fileInboxNote(item.path, item.targetFolder, item.content);
      } else if (item.kind === 'discard') {
        await discardInboxNote(item.path);
      } else if (item.kind === 'acknowledge') {
        await acknowledgeCapture(item.captureId);
      } else if (item.kind === 'flag') {
        await flagCard(item.cardId, item.reason);
      } else if (item.kind === 'feed_accept') {
        await acceptFeedItem(item.itemId);
      } else if (item.kind === 'feed_bin') {
        await binFeedItem(item.itemId);
      } else if (item.kind === 'feed_restore') {
        await restoreFeedItem(item.itemId);
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
