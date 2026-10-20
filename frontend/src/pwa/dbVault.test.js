import { describe, it, expect, vi, beforeEach } from 'vitest';

// Per-vault IDB: the DB name follows the active vault, and flush() never replays another
// vault's outbox entries.
const opened = [];
beforeEach(() => {
  opened.length = 0;
  localStorage.clear();
  globalThis.indexedDB = {
    open: (name) => {
      opened.push(name);
      const req = {};
      setTimeout(() => {
        req.result = { objectStoreNames: { contains: () => true } };
        req.onsuccess?.();
      });
      return req;
    },
  };
  vi.resetModules();
});

describe('db naming', () => {
  it('opens obsopt-offline for vault 1 and obsopt-offline-v<id> otherwise', async () => {
    const { openDB } = await import('./db');
    await openDB();
    localStorage.setItem('activeVaultId', '4');
    await openDB();
    expect(opened).toEqual(['obsopt-offline', 'obsopt-offline-v4']);
  });
});

describe('outbox per vault', () => {
  it('flush skips entries stamped with another vault', async () => {
    localStorage.setItem('activeVaultId', '2');
    vi.doMock('./db', () => ({
      addToOutbox: vi.fn(),
      getOutbox: vi.fn().mockResolvedValue([
        { id: 1, kind: 'grade', notePath: 'a.md', band: 3, vaultId: 1 },
        { id: 2, kind: 'grade', notePath: 'b.md', band: 3, vaultId: 2 },
      ]),
      deleteFromOutbox: vi.fn(),
    }));
    const gradeNote = vi.fn().mockResolvedValue({});
    vi.doMock('../api/notes', () => ({ gradeNote, submitAttempt: vi.fn(), completeAssignment: vi.fn(), flagCard: vi.fn() }));
    vi.doMock('../api/inbox', () => ({ fileInboxNote: vi.fn(), discardInboxNote: vi.fn(), acknowledgeCapture: vi.fn() }));
    vi.doMock('../api/feed', () => ({ acceptFeedItem: vi.fn(), binFeedItem: vi.fn(), restoreFeedItem: vi.fn() }));
    vi.doMock('./captureSentNotify', () => ({ captureSentNotifyEnabled: vi.fn().mockResolvedValue(false), notifyCaptureSent: vi.fn() }));
    const { flush } = await import('./outbox');
    const { deleteFromOutbox } = await import('./db');
    const r = await flush();
    expect(r.sent).toBe(1);
    expect(gradeNote).toHaveBeenCalledTimes(1);
    expect(gradeNote).toHaveBeenCalledWith('b.md', 3);
    expect(deleteFromOutbox).toHaveBeenCalledWith(2);
  });
});
