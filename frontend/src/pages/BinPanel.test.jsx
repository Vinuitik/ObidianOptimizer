import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import BinPanel from './BinPanel';
import { ApiError } from '../api/notes';

vi.mock('../api/bin', () => ({
  fetchBin: vi.fn(), restoreBinItem: vi.fn(), deleteBinItem: vi.fn(), emptyBin: vi.fn(),
}));
import { fetchBin, restoreBinItem, deleteBinItem, emptyBin } from '../api/bin';

const EMPTY = { note: [], folder: [], file: [], feed: [], track: [], track_item: [], subscription: [], unknown: [] };
const ago = h => new Date(Date.now() - h * 3600e3).toISOString();
const FULL = {
  ...EMPTY,
  note: [{ kind: 'note', id: 'n1', label: 'Alpha note', originalPath: '/vault/a/alpha.md', deletedAt: ago(2) }],
  track: [{ kind: 'track', id: '7', label: 'Rust track', deletedAt: ago(30) }],
  track_item: [{ kind: 'track_item', id: '9', label: 'Chapter 1', deletedAt: ago(1) }],
};

beforeEach(() => {
  vi.clearAllMocks();
  fetchBin.mockResolvedValue(FULL);
  restoreBinItem.mockResolvedValue({ restored: true });
  deleteBinItem.mockResolvedValue();
  emptyBin.mockResolvedValue({ removed: 3 });
  vi.spyOn(window, 'confirm').mockReturnValue(true);
});
afterEach(() => vi.restoreAllMocks());

describe('BinPanel', () => {
  it('groups by kind with counts, hides empty segments, folds track_item into Tracks', async () => {
    render(<BinPanel isAuthenticated />);
    expect(await screen.findByText('Alpha note')).toBeTruthy();
    expect(screen.getByRole('tab', { name: /Notes/ })).toBeTruthy();
    expect(screen.getByRole('tab', { name: /Tracks/ }).textContent).toContain('2');
    expect(screen.queryByRole('tab', { name: /Folders/ })).toBeNull();
    expect(screen.getByText('/vault/a/alpha.md')).toBeTruthy();
    expect(screen.getByText(/Deleted 2 h ago/)).toBeTruthy();
    expect(screen.getByText(/deleted forever after 30 days/i)).toBeTruthy();
    fireEvent.click(screen.getByRole('tab', { name: /Tracks/ }));
    expect(screen.getByText('Rust track')).toBeTruthy();
    expect(screen.getByText('Chapter 1')).toBeTruthy();
  });

  it('restores an item and reloads', async () => {
    render(<BinPanel isAuthenticated />);
    await screen.findByText('Alpha note');
    fireEvent.click(screen.getByText('Restore'));
    await waitFor(() => expect(restoreBinItem).toHaveBeenCalledWith('note', 'n1'));
    await waitFor(() => expect(fetchBin).toHaveBeenCalledTimes(2));
  });

  it('uses danger buttons for destructive actions, secondary for Restore', async () => {
    render(<BinPanel isAuthenticated />);
    await screen.findByText('Alpha note');
    expect(screen.getByText('Delete forever').className).toMatch(/danger/);
    expect(screen.getByText('Empty bin').className).toMatch(/danger/);
    expect(screen.getByText('Restore').className).toMatch(/secondary/);
  });

  it('shows a human message per restore error', async () => {
    render(<BinPanel isAuthenticated />);
    await screen.findByText('Alpha note');
    for (const [status, re] of [[404, /no longer in the bin/], [409, /already subscribed/], [410, /saved copy is gone/]]) {
      restoreBinItem.mockRejectedValueOnce(new ApiError(status));
      fireEvent.click(screen.getByText('Restore'));
      await screen.findByText(re);
    }
  });

  it('delete forever asks to confirm, then deletes', async () => {
    window.confirm.mockReturnValueOnce(false);
    render(<BinPanel isAuthenticated />);
    await screen.findByText('Alpha note');
    fireEvent.click(screen.getByText('Delete forever'));
    expect(deleteBinItem).not.toHaveBeenCalled();
    fireEvent.click(screen.getByText('Delete forever'));
    await waitFor(() => expect(deleteBinItem).toHaveBeenCalledWith('note', 'n1'));
  });

  it('empty bin confirms then empties', async () => {
    render(<BinPanel isAuthenticated />);
    await screen.findByText('Alpha note');
    fireEvent.click(screen.getByText('Empty bin'));
    await waitFor(() => expect(emptyBin).toHaveBeenCalled());
  });

  it('shows the empty state', async () => {
    fetchBin.mockResolvedValue(EMPTY);
    render(<BinPanel isAuthenticated />);
    expect(await screen.findByText('Bin is empty.')).toBeTruthy();
    expect(screen.queryByText('Empty bin')).toBeNull();
  });
});
