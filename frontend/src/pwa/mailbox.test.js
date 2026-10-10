import { describe, it, expect, vi, beforeEach } from 'vitest';

// pushMailbox's KINDS set is the allowlist of outbox entries the server's
// MailboxConsumeService actually knows how to drain — an outbox kind missing from it just
// sits in IndexedDB forever (sent neither to the server nor to Drive). This locks in that
// the three Passive Feed swipe kinds (feed/FLOWS_feed.md P2) made the cut.
vi.mock('./setup', () => ({ getCreds: vi.fn() }));
vi.mock('./drive', () => ({
  getAccessToken: vi.fn().mockResolvedValue('token'),
  findOrCreateFolder: vi.fn().mockResolvedValue('folder1'),
  driveCreateFile: vi.fn().mockResolvedValue({}),
}));
vi.mock('./crypto', () => ({
  deriveKey: vi.fn().mockResolvedValue('key'),
  encryptText: vi.fn().mockResolvedValue('encrypted'),
}));
vi.mock('./db', () => ({ getOutbox: vi.fn(), deleteFromOutbox: vi.fn() }));
vi.mock('./captureSentNotify', () => ({
  captureSentNotifyEnabled: vi.fn().mockResolvedValue(false), notifyCaptureSent: vi.fn(),
}));

import { pushMailbox } from './mailbox';
import { getCreds } from './setup';
import { encryptText } from './crypto';
import { getOutbox, deleteFromOutbox } from './db';

const CREDS = { driveFolderId: 'root1', passphrase: 'pw', deviceId: 'dev1' };

describe('pushMailbox — feed kinds', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    getCreds.mockResolvedValue(CREDS);
    encryptText.mockResolvedValue('encrypted');
  });

  it('sends feed_accept/feed_bin/feed_restore (not silently dropped)', async () => {
    getOutbox.mockResolvedValue([
      { id: 1, kind: 'feed_accept', itemId: 5, eventId: 'e1' },
      { id: 2, kind: 'feed_bin', itemId: 6, eventId: 'e2' },
      { id: 3, kind: 'feed_restore', itemId: 7, eventId: 'e3' },
    ]);

    const res = await pushMailbox();

    expect(res).toEqual({ pushed: 3 });
    const [, payload] = encryptText.mock.calls[0];
    const envelope = JSON.parse(payload);
    expect(envelope.events).toHaveLength(3);
    expect(envelope.events.map(e => e.kind)).toEqual(['feed_accept', 'feed_bin', 'feed_restore']);
    expect(deleteFromOutbox).toHaveBeenCalledTimes(3);
  });

  it('an unknown kind is left in the outbox (not sent, not deleted)', async () => {
    getOutbox.mockResolvedValue([{ id: 9, kind: 'someFutureKind', eventId: 'e9' }]);

    const res = await pushMailbox();

    expect(res).toEqual({ pushed: 0 });
    expect(deleteFromOutbox).not.toHaveBeenCalled();
  });

  it('no Drive link yet → no-op, nothing lost', async () => {
    getCreds.mockResolvedValue(null);
    getOutbox.mockResolvedValue([{ id: 1, kind: 'feed_accept', itemId: 5, eventId: 'e1' }]);

    const res = await pushMailbox();

    expect(res).toEqual({ pushed: 0 });
    expect(deleteFromOutbox).not.toHaveBeenCalled();
  });
});
