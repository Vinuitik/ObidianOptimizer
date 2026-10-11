import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import TracksPage from './TracksPage';

const fn = () => vi.fn().mockResolvedValue([]);
const state = {
  isAuthenticated: true, tracks: [],
  trackItems: {}, trackSchedules: {}, trackCapacity: {}, trackProgress: [],
  todayItems: [], todayMode: 'normal', todayOverBudget: false, todayPlanned: 0, todayCapacity: 4,
  showToast: vi.fn(), setTrackMode: vi.fn(), completeTrackItem: vi.fn(),
  fetchTracks: fn(), fetchTrackItems: fn(), fetchTrackCapacity: fn(),
  fetchTrackSchedule: fn(), fetchTodayPlan: fn(), fetchTrackProgress: fn(),
};
vi.mock('../store/useStore', () => ({ default: vi.fn(sel => sel(state)) }));
vi.mock('../api/capture', () => ({ captureResource: vi.fn(), captureFile: vi.fn() }));
vi.mock('../api/notes', () => ({ searchNotes: vi.fn().mockResolvedValue([]) }));
vi.mock('../api/tracks', () => ({
  generateMinicourse: vi.fn(), fetchMinicourseJob: vi.fn(), approveMinicourse: vi.fn(),
  importCsv: vi.fn(), commitImport: vi.fn(),
}));

beforeEach(() => {
  state.tracks = [];
  state.todayItems = [];
  state.todayMode = 'normal';
});

describe('TodayTab', () => {
  it('empty state CTA switches to Manage tab', () => {
    render(<TracksPage />);
    fireEvent.click(screen.getByText('Create your first track'));
    expect(screen.getByLabelText('Track title')).toBeTruthy();
  });

  it('renders reason and "N of M for today" header', () => {
    state.tracks = [{ id: 1, title: 'Rust' }];
    state.todayItems = [
      { itemId: 1, trackId: 1, trackTitle: 'Rust', title: 'Ch 1', reason: 'Due Fri · ~3/day to finish' },
      { itemId: 2, trackId: 1, trackTitle: 'Rust', title: 'Ch 2', reason: 'Fills leftover capacity' },
    ];
    render(<TracksPage />);
    expect(screen.getByText('2 of 4 for today')).toBeTruthy();
    expect(screen.getByText('Due Fri · ~3/day to finish')).toBeTruthy();
    expect(screen.getByText('Fills leftover capacity')).toBeTruthy();
  });

  it('uses renamed mode labels', () => {
    state.todayMode = 'lockin';
    render(<TracksPage />);
    expect(screen.getByText('Focus on one track')).toBeTruthy();
    expect(screen.getByText('Switch to Balanced')).toBeTruthy();
  });
});
