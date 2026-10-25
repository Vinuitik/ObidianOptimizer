import { render, screen, waitFor, fireEvent, act } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import SettingsPage from './SettingsPage';
import { parseField } from './useAutosaveSettings';
import useStore from '../store/useStore';

vi.mock('../api/notes', () => ({
  fetchChronoStatus: vi.fn().mockResolvedValue({ lastRunDate: '2026-10-10' }),
  runChronoNow: vi.fn(),
  fetchChildren: vi.fn().mockResolvedValue({ parentPath: '/vault', folderPaths: [] }),
  fetchHostChildren: vi.fn(),
  fetchVaultHostPath: vi.fn().mockResolvedValue({ path: '/home/me/vault' }),
}));
vi.mock('../api/bin', () => ({
  fetchBin: vi.fn().mockResolvedValue({}), restoreBinItem: vi.fn(), deleteBinItem: vi.fn(), emptyBin: vi.fn(),
}));
vi.mock('../api/sync', () => ({
  fetchSyncStatus: vi.fn().mockResolvedValue({ connected: false }),
  fetchOAuthUrl: vi.fn(), disconnectDrive: vi.fn(),
  triggerFullBackup: vi.fn(), triggerDbRestore: vi.fn(),
}));

const SETTINGS = {
  vaultPath: '/vault', resourcePath: '/vault/res', reviewPageSize: 20, startupSyncMode: 'blocking',
  flashcardsEnabled: true, tracksEnabled: true, feedAutoAccept: false,
  maxDailyReviews: 50, maxDailyFlashcards: 20, chronicNeglectDays: 30, bankruptcyLimit: 100,
};

let applySettings;
beforeEach(() => {
  applySettings = vi.fn(async patch => { useStore.setState(s => ({ settings: { ...s.settings, ...patch } })); });
  useStore.setState({ settings: SETTINGS, applySettings, isAuthenticated: true, showToast: vi.fn() });
});
afterEach(() => { vi.clearAllMocks(); useStore.setState({ isAuthenticated: false }); });

describe('SettingsPage autosave', () => {
  it('saves a number field on blur and shows Saved', async () => {
    render(<SettingsPage />);
    const input = await screen.findByLabelText(/Max reviews per day/);
    fireEvent.change(input, { target: { value: '40' } });
    expect(applySettings).not.toHaveBeenCalled();
    fireEvent.blur(input);
    await waitFor(() => expect(applySettings).toHaveBeenCalledWith({ maxDailyReviews: 40 }));
    expect(await screen.findByText('Saved ✓')).toBeInTheDocument();
  });

  it('saves a select immediately on change with the typed value', async () => {
    render(<SettingsPage />);
    const sel = await screen.findByLabelText(/Tracks tab/);
    fireEvent.change(sel, { target: { value: 'false' } });
    await waitFor(() => expect(applySettings).toHaveBeenCalledWith({ tracksEnabled: false }));
  });

  it('does not call the API when blurred without a change', async () => {
    render(<SettingsPage />);
    const input = await screen.findByLabelText(/Max reviews per day/);
    fireEvent.blur(input);
    await act(async () => {});
    expect(applySettings).not.toHaveBeenCalled();
  });

  it('shows an inline error and does not save an invalid number', async () => {
    render(<SettingsPage />);
    const input = await screen.findByLabelText(/Notes loaded per review/);
    fireEvent.change(input, { target: { value: '9999' } });
    fireEvent.blur(input);
    expect(await screen.findByRole('alert')).toHaveTextContent(/between 1 and 500/);
    expect(applySettings).not.toHaveBeenCalled();
  });

  it('surfaces a failed save inline', async () => {
    applySettings.mockRejectedValueOnce(new Error('400'));
    render(<SettingsPage />);
    const input = await screen.findByLabelText(/Max reviews per day/);
    fireEvent.change(input, { target: { value: '10' } });
    fireEvent.blur(input);
    expect(await screen.findByText(/Couldn’t save/)).toBeInTheDocument();
  });

  it('groups settings under Vault, Learning, Feed, Sync, Advanced', async () => {
    render(<SettingsPage />);
    for (const t of ['Vault', 'Learning', 'Feed', 'Sync', 'Advanced']) {
      expect(await screen.findByRole('heading', { name: t })).toBeInTheDocument();
    }
  });
});

describe('parseField', () => {
  it('maps select values back to their typed option', () => {
    const f = { label: 'x', type: 'select', options: [{ value: true }, { value: false }] };
    expect(parseField(f, 'false')).toEqual({ value: false });
  });
  it('rejects empty text', () => {
    expect(parseField({ label: 'Path', type: 'path' }, '  ').error).toMatch(/can’t be empty/);
  });
});
