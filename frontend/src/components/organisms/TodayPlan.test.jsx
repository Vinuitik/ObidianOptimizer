import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import TodayPlan from './TodayPlan';

// Tiny reactive store so Today re-renders when completeTrackItem removes a row.
const h = vi.hoisted(() => ({ state: {}, listeners: new Set() }));
const fn = () => vi.fn().mockResolvedValue([]);
function setState(patch) { Object.assign(h.state, patch); h.listeners.forEach(l => l()); }
function resetState() {
  Object.keys(h.state).forEach(k => delete h.state[k]);
  Object.assign(h.state, {
    isAuthenticated: true, tracks: [],
    trackItems: {}, trackSchedules: {}, trackCapacity: {}, trackProgress: [],
    todayItems: [], todayMode: 'normal', todayOverBudget: false, todayPlanned: 0, todayCapacity: 4,
    showToast: vi.fn(), setTrackMode: vi.fn(), completeTrackItem: vi.fn(),
    fetchTracks: fn(), fetchTrackItems: fn(), fetchTrackCapacity: fn(),
    fetchTrackSchedule: fn(), fetchTodayPlan: fn(), fetchTrackProgress: fn(),
  });
}
vi.mock('../../store/useStore', async () => {
  const { useSyncExternalStore } = await import('react');
  const useStore = sel => useSyncExternalStore(
    cb => { h.listeners.add(cb); return () => h.listeners.delete(cb); },
    () => sel(h.state),
  );
  useStore.getState = () => h.state;
  return { default: useStore };
});
vi.mock('../../api/capture', () => ({ captureResource: vi.fn(), captureFile: vi.fn() }));
vi.mock('../../api/notes', () => ({ searchNotes: vi.fn().mockResolvedValue([]) }));
vi.mock('../../api/tracks', () => ({
  generateMinicourse: vi.fn(), fetchMinicourseJob: vi.fn(), approveMinicourse: vi.fn(),
  importCsv: vi.fn(), commitImport: vi.fn(),
}));

const onCreate = vi.fn(); const onManage = vi.fn();
beforeEach(() => { onCreate.mockClear(); onManage.mockClear(); resetState(); localStorage.clear(); });

describe('TodayPlan', () => {
  it('empty state CTA calls onCreateTrack', async () => {
    render(<TodayPlan onCreateTrack={onCreate} onManage={onManage} />);
    fireEvent.click(await screen.findByText('Create your first track'));
    expect(onCreate).toHaveBeenCalled();
  });

  it('shows a skeleton before the first fetch resolves, not the empty state', () => {
    render(<TodayPlan onCreateTrack={onCreate} onManage={onManage} />);
    expect(screen.queryByText('Create your first track')).toBeNull();
  });

  it('renders reason and "N of M for today" header', async () => {
    setState({
      tracks: [{ id: 1, title: 'Rust' }],
      todayItems: [
        { itemId: 1, trackId: 1, trackTitle: 'Rust', title: 'Ch 1', reason: 'Due Fri · ~3/day to finish' },
        { itemId: 2, trackId: 1, trackTitle: 'Rust', title: 'Ch 2', reason: 'Fills leftover capacity' },
      ],
    });
    render(<TodayPlan onCreateTrack={onCreate} onManage={onManage} />);
    expect(await screen.findByText('2 of 4 for today')).toBeTruthy();
    expect(screen.getByText('Due Fri · ~3/day to finish')).toBeTruthy();
    expect(screen.getByText('Fills leftover capacity')).toBeTruthy();
  });

  it('uses renamed mode labels and explains the mode', async () => {
    setState({ todayMode: 'lockin' });
    render(<TodayPlan onCreateTrack={onCreate} onManage={onManage} />);
    expect(await screen.findByText('Focus on one track')).toBeTruthy();
    expect(screen.getByText('Switch to Balanced')).toBeTruthy();
    expect(screen.getByText(/Only your most urgent track/)).toBeTruthy();
  });

  it('Mark done collapses the row out and toasts "Done"', async () => {
    setState({
      tracks: [{ id: 1, title: 'Rust' }],
      todayItems: [
        { itemId: 1, trackId: 1, trackTitle: 'Rust', title: 'Ch 1' },
        { itemId: 2, trackId: 1, trackTitle: 'Rust', title: 'Ch 2' },
      ],
      completeTrackItem: vi.fn(async id => {
        setState({ todayItems: h.state.todayItems.filter(i => i.itemId !== id) });
      }),
    });
    render(<TodayPlan onCreateTrack={onCreate} onManage={onManage} />);
    await screen.findByText('Ch 1');
    fireEvent.click(screen.getAllByText('Mark done')[0]);
    await waitFor(() => expect(h.state.completeTrackItem).toHaveBeenCalledWith(1, false));
    expect(h.state.showToast).toHaveBeenCalledWith({ message: 'Done', tone: 'success' });
    await waitFor(() => expect(screen.queryByText('Ch 1')).toBeNull());
    expect(screen.getByText('Ch 2')).toBeTruthy();
  });

  it('remembers add-to-review per track', async () => {
    setState({
      tracks: [{ id: 1, title: 'Rust' }],
      todayItems: [{ itemId: 1, trackId: 1, trackTitle: 'Rust', title: 'Ch 1', notePath: 'a.md' }],
      completeTrackItem: vi.fn().mockResolvedValue({}),
    });
    render(<TodayPlan onCreateTrack={onCreate} onManage={onManage} />);
    fireEvent.click(await screen.findByLabelText('Add to spaced review'));
    expect(localStorage.getItem('tracks:addToReview:1')).toBe('1');
    fireEvent.click(screen.getByText('Mark done'));
    await waitFor(() => expect(h.state.completeTrackItem).toHaveBeenCalledWith(1, true));
  });
});
