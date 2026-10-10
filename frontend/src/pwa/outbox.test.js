import { describe, it, expect, vi, beforeEach } from 'vitest';

// flush() previously only replayed 'grade'/'capture'/'captureText'/'captureFile' — the
// other four kinds (assignment, file, discard, acknowledge, flag) were enqueued but had no
// branch in flush()'s if/else chain, so they sat in the outbox forever: never sent, never
// deleted, no error. This file locks in that every enqueue* kind actually gets replayed.
vi.mock('./db', () => ({
  addToOutbox: vi.fn(),
  getOutbox: vi.fn(),
  deleteFromOutbox: vi.fn(),
}));
vi.mock('../api/notes', () => ({
  gradeNote: vi.fn(), submitAttempt: vi.fn(), completeAssignment: vi.fn(), flagCard: vi.fn(),
}));
vi.mock('../api/inbox', () => ({
  fileInboxNote: vi.fn(), discardInboxNote: vi.fn(), acknowledgeCapture: vi.fn(),
}));
vi.mock('../api/feed', () => ({
  acceptFeedItem: vi.fn(), binFeedItem: vi.fn(), restoreFeedItem: vi.fn(),
}));
vi.mock('./captureSentNotify', () => ({
  captureSentNotifyEnabled: vi.fn().mockResolvedValue(false), notifyCaptureSent: vi.fn(),
}));

import { flush } from './outbox';
import { getOutbox, deleteFromOutbox } from './db';
import { submitAttempt, completeAssignment, flagCard } from '../api/notes';
import { fileInboxNote, discardInboxNote, acknowledgeCapture } from '../api/inbox';
import { acceptFeedItem, binFeedItem, restoreFeedItem } from '../api/feed';

describe('flush', () => {
  beforeEach(() => vi.clearAllMocks());

  it('replays a queued assignment: each answer via submitAttempt, then completeAssignment', async () => {
    getOutbox.mockResolvedValue([{
      id: 1, kind: 'assignment', assignmentId: 'a1', notePath: 'n.md',
      answers: { c1: 'x', c2: 'y' },
    }]);
    submitAttempt.mockResolvedValue({});
    completeAssignment.mockResolvedValue({ notes: [] });

    const { sent, failed } = await flush();

    expect(submitAttempt).toHaveBeenCalledWith('a1', 'c1', 'x');
    expect(submitAttempt).toHaveBeenCalledWith('a1', 'c2', 'y');
    expect(completeAssignment).toHaveBeenCalledWith('a1');
    expect(deleteFromOutbox).toHaveBeenCalledWith(1);
    expect(sent).toBe(1);
    expect(failed).toBe(0);
  });

  it('replays a queued inbox file', async () => {
    getOutbox.mockResolvedValue([{ id: 2, kind: 'file', path: 'p.md', targetFolder: 'F', content: 'c' }]);
    fileInboxNote.mockResolvedValue({});

    await flush();

    expect(fileInboxNote).toHaveBeenCalledWith('p.md', 'F', 'c');
    expect(deleteFromOutbox).toHaveBeenCalledWith(2);
  });

  it('replays a queued inbox discard', async () => {
    getOutbox.mockResolvedValue([{ id: 3, kind: 'discard', path: 'p.md' }]);
    discardInboxNote.mockResolvedValue({});

    await flush();

    expect(discardInboxNote).toHaveBeenCalledWith('p.md');
    expect(deleteFromOutbox).toHaveBeenCalledWith(3);
  });

  it('replays a queued acknowledge', async () => {
    getOutbox.mockResolvedValue([{ id: 4, kind: 'acknowledge', captureId: 'cap1' }]);
    acknowledgeCapture.mockResolvedValue({});

    await flush();

    expect(acknowledgeCapture).toHaveBeenCalledWith('cap1');
    expect(deleteFromOutbox).toHaveBeenCalledWith(4);
  });

  it('replays a queued flag', async () => {
    getOutbox.mockResolvedValue([{ id: 5, kind: 'flag', cardId: 'c9', reason: 'bad card' }]);
    flagCard.mockResolvedValue({});

    await flush();

    expect(flagCard).toHaveBeenCalledWith('c9', 'bad card');
    expect(deleteFromOutbox).toHaveBeenCalledWith(5);
  });

  it('a failed replay stays queued (not deleted) and counts as failed', async () => {
    getOutbox.mockResolvedValue([{ id: 6, kind: 'discard', path: 'p.md' }]);
    discardInboxNote.mockRejectedValue(new Error('503'));

    const { sent, failed } = await flush();

    expect(deleteFromOutbox).not.toHaveBeenCalled();
    expect(sent).toBe(0);
    expect(failed).toBe(1);
  });

  // Passive Feed offline swipe (feed/FLOWS_feed.md P2) — the server-direct outbox replay
  // path, alongside the Drive-mailbox path (mailbox.test.js).
  it('replays a queued feed accept', async () => {
    getOutbox.mockResolvedValue([{ id: 7, kind: 'feed_accept', itemId: 42 }]);
    acceptFeedItem.mockResolvedValue({ captureId: 'c1' });

    await flush();

    expect(acceptFeedItem).toHaveBeenCalledWith(42);
    expect(deleteFromOutbox).toHaveBeenCalledWith(7);
  });

  it('replays a queued feed bin', async () => {
    getOutbox.mockResolvedValue([{ id: 8, kind: 'feed_bin', itemId: 43 }]);
    binFeedItem.mockResolvedValue();

    await flush();

    expect(binFeedItem).toHaveBeenCalledWith(43);
    expect(deleteFromOutbox).toHaveBeenCalledWith(8);
  });

  it('replays a queued feed restore', async () => {
    getOutbox.mockResolvedValue([{ id: 9, kind: 'feed_restore', itemId: 44 }]);
    restoreFeedItem.mockResolvedValue();

    await flush();

    expect(restoreFeedItem).toHaveBeenCalledWith(44);
    expect(deleteFromOutbox).toHaveBeenCalledWith(9);
  });

  // A restore queued before the server let the ingest worker claim the capture is a genuine
  // conflict (409), not a transient failure — stays queued, retried forever (same documented
  // gap as the mailbox path; see outbox.js "feed_restore" comment).
  it('a feed restore that 409s stays queued, not treated as sent', async () => {
    getOutbox.mockResolvedValue([{ id: 10, kind: 'feed_restore', itemId: 45 }]);
    restoreFeedItem.mockRejectedValue(new Error('409'));

    const { sent, failed } = await flush();

    expect(deleteFromOutbox).not.toHaveBeenCalled();
    expect(sent).toBe(0);
    expect(failed).toBe(1);
  });
});
