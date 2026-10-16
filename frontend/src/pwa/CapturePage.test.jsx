import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import useStore from '../store/useStore';
import CapturePage from './CapturePage';

// c31a125 — fix(capture): fall back to cached track list when offline fetch fails.
// Before the fix, a failed fetchTracks() was swallowed with .catch(() => {}) and the
// picker was simply left at its initial empty array, even with a previously cached list
// on hand.

const mockFetchTracks = vi.fn();
vi.mock('../api/tracks', () => ({ fetchTracks: (...a) => mockFetchTracks(...a) }));

const mockGetMeta = vi.fn();
const mockSetMeta = vi.fn();
vi.mock('./db', () => ({
  getMeta: (...a) => mockGetMeta(...a),
  setMeta: (...a) => mockSetMeta(...a),
}));

function setup() {
  useStore.setState({ isAuthenticated: true });
  render(<MemoryRouter><CapturePage /></MemoryRouter>);
}

beforeEach(() => {
  vi.clearAllMocks();
  mockGetMeta.mockResolvedValue(null);
  mockSetMeta.mockResolvedValue(undefined);
});

describe('CapturePage — track picker offline fallback', () => {
  it('a failed fetch falls back to the last cached track list instead of leaving the picker empty', async () => {
    mockFetchTracks.mockRejectedValue(new TypeError('Failed to fetch'));
    mockGetMeta.mockResolvedValue([{ id: 7, title: 'Cached Track', status: 'active' }]);

    setup();

    await waitFor(() => expect(screen.getByText('Cached Track')).toBeTruthy());
  });

  it('a successful fetch caches the list for the next offline failure', async () => {
    mockFetchTracks.mockResolvedValue([{ id: 1, title: 'Live Track', status: 'active' }]);

    setup();

    await waitFor(() => expect(screen.getByText('Live Track')).toBeTruthy());
    expect(mockSetMeta).toHaveBeenCalledWith('cachedTracks', [{ id: 1, title: 'Live Track', status: 'active' }]);
  });
});
