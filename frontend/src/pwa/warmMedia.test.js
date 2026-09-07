import { describe, it, expect, vi, beforeEach } from 'vitest';

vi.mock('./db', () => ({ getAllReviewNotes: vi.fn(), getMeta: vi.fn() }));
vi.mock('../utils/noteMedia', () => ({ mediaEntriesForNote: vi.fn() }));
vi.mock('./setup', () => ({ getCreds: vi.fn() }));
vi.mock('./drive', () => ({
  getAccessToken: vi.fn(), driveFindByVaultPath: vi.fn(), driveDownload: vi.fn(),
}));
vi.mock('./crypto', () => ({ deriveKey: vi.fn().mockResolvedValue('key'), decrypt: vi.fn() }));

import { warmReviewMedia } from './warmMedia';
import { getAllReviewNotes, getMeta } from './db';
import { mediaEntriesForNote } from '../utils/noteMedia';
import { getCreds } from './setup';
import { getAccessToken, driveFindByVaultPath, driveDownload } from './drive';
import { decrypt } from './crypto';

// Minimal fake Cache Storage — jsdom has none. Good enough for what warmReviewMedia does:
// put() by key, keys() for retention, delete() for eviction.
function fakeCache() {
  const store = new Map();
  return {
    async put(key, res) { store.set(key, res); },
    async keys() { return [...store.keys()].map(k => ({ url: 'https://example.test' + k })); },
    async delete(req) {
      const k = typeof req === 'string' ? req : new URL(req.url).pathname + new URL(req.url).search;
      return store.delete(k);
    },
  };
}

describe('warmReviewMedia — Drive fallback for raw vault resources', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    global.caches = { open: vi.fn().mockResolvedValue(fakeCache()) };
    global.fetch = vi.fn();
    getAllReviewNotes.mockResolvedValue([{ content: 'note text', source: null }]);
    getMeta.mockResolvedValue([]);
  });

  it('server fetch succeeds → caches directly, never touches Drive', async () => {
    mediaEntriesForNote.mockReturnValue([
      { key: '/vault-media/images/a.png', fetch: '/vault-media/images/a.png' },
    ]);
    global.fetch.mockResolvedValue({ ok: true });

    const res = await warmReviewMedia({});

    expect(res.warmed).toBe(1);
    expect(getAccessToken).not.toHaveBeenCalled();
  });

  it('server unreachable, raw vault resource (image) → falls back to Drive', async () => {
    mediaEntriesForNote.mockReturnValue([
      { key: '/vault-media/images/a.png', fetch: '/vault-media/images/a.png' },
    ]);
    global.fetch.mockRejectedValue(new TypeError('offline'));
    getCreds.mockResolvedValue({ driveFolderId: 'root1', passphrase: 'pw' });
    getAccessToken.mockResolvedValue('tok');
    driveFindByVaultPath.mockResolvedValue({ id: 'f1' });
    driveDownload.mockResolvedValue(new Uint8Array([1, 2, 3]));
    decrypt.mockResolvedValue(new Uint8Array([9, 9, 9]));

    const res = await warmReviewMedia({});

    expect(driveFindByVaultPath).toHaveBeenCalledWith('tok', 'resources/images/a.png');
    expect(res.warmed).toBe(1);
  });

  it('server unreachable, A/V rendition (fetch !== key) → NOT attempted via Drive', async () => {
    mediaEntriesForNote.mockReturnValue([
      { key: '/vault-media/video/a.mp4', fetch: '/media-rendition?path=resources/video/a.mp4' },
    ]);
    global.fetch.mockRejectedValue(new TypeError('offline'));
    getCreds.mockResolvedValue({ driveFolderId: 'root1', passphrase: 'pw' });

    const res = await warmReviewMedia({});

    expect(driveFindByVaultPath).not.toHaveBeenCalled();
    expect(res.warmed).toBe(0);
  });

  it('server unreachable, PDF page → NOT attempted via Drive', async () => {
    mediaEntriesForNote.mockReturnValue([
      { key: '/pdf-page?path=resources/x.pdf&page=1', fetch: '/pdf-page?path=resources/x.pdf&page=1' },
    ]);
    global.fetch.mockRejectedValue(new TypeError('offline'));

    const res = await warmReviewMedia({});

    expect(driveFindByVaultPath).not.toHaveBeenCalled();
    expect(res.warmed).toBe(0);
  });

  it('not Drive-linked → Drive fallback no-ops instead of throwing', async () => {
    mediaEntriesForNote.mockReturnValue([
      { key: '/vault-media/images/a.png', fetch: '/vault-media/images/a.png' },
    ]);
    global.fetch.mockRejectedValue(new TypeError('offline'));
    getCreds.mockResolvedValue(null);

    const res = await warmReviewMedia({});

    expect(res.warmed).toBe(0);
  });

  it('Drive file not found → no-ops instead of throwing', async () => {
    mediaEntriesForNote.mockReturnValue([
      { key: '/vault-media/images/a.png', fetch: '/vault-media/images/a.png' },
    ]);
    global.fetch.mockRejectedValue(new TypeError('offline'));
    getCreds.mockResolvedValue({ driveFolderId: 'root1', passphrase: 'pw' });
    getAccessToken.mockResolvedValue('tok');
    driveFindByVaultPath.mockResolvedValue(null);

    const res = await warmReviewMedia({});

    expect(res.warmed).toBe(0);
    expect(driveDownload).not.toHaveBeenCalled();
  });
});
