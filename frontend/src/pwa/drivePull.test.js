import { describe, it, expect, vi, beforeEach } from 'vitest';

vi.mock('./setup', () => ({ getCreds: vi.fn(), refreshCreds: vi.fn() }));
vi.mock('./drive', () => ({ getAccessToken: vi.fn(), driveList: vi.fn(), driveDownload: vi.fn() }));
vi.mock('./crypto', () => ({ deriveKey: vi.fn().mockResolvedValue('key'), decryptText: vi.fn() }));
vi.mock('./db', () => ({
  putReviewNotes: vi.fn(), putAssignments: vi.fn(), setMeta: vi.fn(),
  getMeta: vi.fn(), getAllReviewNotes: vi.fn(),
}));
vi.mock('../api/notes', () => ({ fetchNames: vi.fn() }));

import { pullReviewFromDrive } from './drivePull';
import { getCreds } from './setup';
import { driveList, driveDownload } from './drive';
import { decryptText } from './crypto';
import { putReviewNotes, setMeta, getMeta, getAllReviewNotes } from './db';

const CREDS = { driveFolderId: 'root1', passphrase: 'pw' };

// Real Drive/decrypt behavior is mocked away entirely — this file's own logic (finding the
// _offline folder, then the named bundle inside it) is exercised by pointing driveList at
// canned answers keyed on what its query is asking for, not by faking real Drive responses.
function stubBundle(notes) {
  driveList.mockImplementation(async (token, query) => {
    if (query.includes("name='_offline'")) return [{ id: 'offlineFolder' }];
    if (query.includes("name='review-bundle.json.enc'")) return [{ id: 'reviewFile' }];
    return []; // cards/inbox bundles: not found → those best-effort sections just no-op
  });
  driveDownload.mockImplementation(async (_token, fileId) => fileId);
  decryptText.mockImplementation(async (_key, bytes) =>
    bytes === 'reviewFile' ? JSON.stringify({ notes, generatedAt: 123 }) : '{}');
}

describe('pullReviewFromDrive — daily pin', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    getCreds.mockResolvedValue(CREDS);
    getAllReviewNotes.mockResolvedValue([{ path: 'existing.md' }]); // "already on the phone"
  });

  it('first pull of the day writes the list and records today\'s date', async () => {
    getMeta.mockResolvedValue(null); // no prior pin
    stubBundle([{ path: 'n1.md', shortName: 'n1', content: '' }]);

    const res = await pullReviewFromDrive({});

    expect(putReviewNotes).toHaveBeenCalledWith([
      expect.objectContaining({ path: 'n1.md' }),
    ]);
    expect(setMeta).toHaveBeenCalledWith('reviewListDate', new Date().toISOString().slice(0, 10));
    expect(res.listPinned).toBe(false);
    expect(res.notes).toBe(1);
  });

  it('a second pull the SAME day does not overwrite the list', async () => {
    getMeta.mockImplementation(async (k) =>
      k === 'reviewListDate' ? new Date().toISOString().slice(0, 10) : null);
    stubBundle([{ path: 'n2.md', shortName: 'n2', content: '' }]); // different from what's local

    const res = await pullReviewFromDrive({});

    expect(putReviewNotes).not.toHaveBeenCalled();
    expect(res.listPinned).toBe(true);
    // notes reports the EXISTING local count, not the freshly (and now discarded) pulled one
    expect(res.notes).toBe(1);
  });

  it('force=true overwrites the list even on the same day (manual "Download for offline")', async () => {
    getMeta.mockImplementation(async (k) =>
      k === 'reviewListDate' ? new Date().toISOString().slice(0, 10) : null);
    stubBundle([{ path: 'n3.md', shortName: 'n3', content: '' }]);

    const res = await pullReviewFromDrive({ force: true });

    expect(putReviewNotes).toHaveBeenCalledWith([
      expect.objectContaining({ path: 'n3.md' }),
    ]);
    expect(res.listPinned).toBe(false);
  });

  it('a new calendar day prepares a fresh list again', async () => {
    getMeta.mockImplementation(async (k) => (k === 'reviewListDate' ? '2000-01-01' : null));
    stubBundle([{ path: 'n4.md', shortName: 'n4', content: '' }]);

    const res = await pullReviewFromDrive({});

    expect(putReviewNotes).toHaveBeenCalled();
    expect(res.listPinned).toBe(false);
  });
});
