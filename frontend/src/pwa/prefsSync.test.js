import { describe, it, expect, vi, beforeEach } from 'vitest';

vi.mock('./setup', () => ({ getCreds: vi.fn() }));
vi.mock('./drive', () => ({
  getAccessToken: vi.fn(), driveList: vi.fn(), driveCreateFile: vi.fn(),
  driveUpdateFile: vi.fn(), driveDownload: vi.fn(), findOrCreateFolder: vi.fn(),
}));
vi.mock('./crypto', () => ({
  deriveKey: vi.fn().mockResolvedValue('key'),
  encryptText: vi.fn().mockImplementation(async (_key, str) => new TextEncoder().encode(`enc:${str}`)),
  decryptText: vi.fn().mockImplementation(async (_key, bytes) =>
    new TextDecoder().decode(bytes).replace(/^enc:/, '')),
}));

import { readPrefs, writePrefs } from './prefsSync';
import { getCreds } from './setup';
import { getAccessToken, driveList, driveCreateFile, driveUpdateFile, driveDownload, findOrCreateFolder } from './drive';

const CREDS = { driveFolderId: 'root1', passphrase: 'pw' };

describe('readPrefs', () => {
  beforeEach(() => vi.clearAllMocks());

  it('not Drive-linked → null, never touches the network', async () => {
    getCreds.mockResolvedValue(null);
    expect(await readPrefs()).toBeNull();
    expect(getAccessToken).not.toHaveBeenCalled();
  });

  it('linked but no prefs file yet → null', async () => {
    getCreds.mockResolvedValue(CREDS);
    getAccessToken.mockResolvedValue('tok');
    findOrCreateFolder.mockResolvedValue('folder1');
    driveList.mockResolvedValue([]);

    expect(await readPrefs()).toBeNull();
    expect(driveDownload).not.toHaveBeenCalled();
  });

  it('linked with an existing file → decrypts and parses it', async () => {
    getCreds.mockResolvedValue(CREDS);
    getAccessToken.mockResolvedValue('tok');
    findOrCreateFolder.mockResolvedValue('folder1');
    driveList.mockResolvedValue([{ id: 'file1', name: 'settings.json.enc' }]);
    driveDownload.mockResolvedValue(new TextEncoder().encode('enc:{"rsvpWpm":425}'));

    expect(await readPrefs()).toEqual({ rsvpWpm: 425 });
  });

  it('a thrown error anywhere → null, never propagates', async () => {
    getCreds.mockRejectedValue(new Error('IDB unavailable'));
    expect(await readPrefs()).toBeNull();
  });
});

describe('writePrefs', () => {
  beforeEach(() => vi.clearAllMocks());

  it('not Drive-linked → no-op, never touches the network', async () => {
    getCreds.mockResolvedValue(null);
    await writePrefs({ rsvpWpm: 500 });
    expect(getAccessToken).not.toHaveBeenCalled();
  });

  it('no existing file → creates one with just the patch', async () => {
    getCreds.mockResolvedValue(CREDS);
    getAccessToken.mockResolvedValue('tok');
    findOrCreateFolder.mockResolvedValue('folder1');
    driveList.mockResolvedValue([]);

    await writePrefs({ rsvpWpm: 500 });

    expect(driveUpdateFile).not.toHaveBeenCalled();
    expect(driveCreateFile).toHaveBeenCalledWith(
      'tok',
      { name: 'settings.json.enc', parents: ['folder1'] },
      new TextEncoder().encode('enc:{"rsvpWpm":500}'),
    );
  });

  it('existing file → merges the patch into it and updates IN PLACE (same id)', async () => {
    getCreds.mockResolvedValue(CREDS);
    getAccessToken.mockResolvedValue('tok');
    findOrCreateFolder.mockResolvedValue('folder1');
    driveList.mockResolvedValue([{ id: 'file1', name: 'settings.json.enc' }]);
    driveDownload.mockResolvedValue(new TextEncoder().encode('enc:{"otherField":"keep me"}'));

    await writePrefs({ rsvpWpm: 500 });

    expect(driveCreateFile).not.toHaveBeenCalled();
    expect(driveUpdateFile).toHaveBeenCalledWith(
      'tok', 'file1',
      new TextEncoder().encode('enc:{"otherField":"keep me","rsvpWpm":500}'),
    );
  });

  it('a thrown error anywhere is swallowed, never propagates', async () => {
    getCreds.mockRejectedValue(new Error('IDB unavailable'));
    await expect(writePrefs({ rsvpWpm: 500 })).resolves.toBeUndefined();
  });
});
