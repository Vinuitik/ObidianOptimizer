import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import WeekPlan from './WeekPlan';

const fn = () => vi.fn().mockResolvedValue([]);
const state = {
  isAuthenticated: true,
  tracks: [{ id: 5, title: 'Rust', type: 'book', status: 'active', deadline: null, includeInProgress: false }],
  trackItems: {}, trackSchedules: {}, trackCapacity: {},
  showToast: vi.fn(), createTrack: vi.fn(), updateTrack: vi.fn(), addTrackItem: vi.fn(), updateSchedule: vi.fn(),
  fetchTracks: fn(), fetchTrackItems: fn(), fetchTrackCapacity: fn(), fetchTrackSchedule: fn(), fetchTodayPlan: fn(),
};
vi.mock('../../store/useStore', () => ({ default: vi.fn(sel => sel(state)) }));
vi.mock('../../api/capture', () => ({ captureResource: vi.fn(), captureFile: vi.fn() }));
vi.mock('../../api/notes', () => ({ searchNotes: vi.fn().mockResolvedValue([]) }));
const mockWeek = vi.fn();
vi.mock('../../api/tracks', () => ({
  fetchWeekPlan: (...a) => mockWeek(...a),
  mondayOf: () => '2026-10-19',
  generateMinicourse: vi.fn(), fetchMinicourseJob: vi.fn(), approveMinicourse: vi.fn(),
  importCsv: vi.fn(), commitImport: vi.fn(),
}));

const days = Array.from({ length: 7 }, (_, i) => ({
  date: `2026-10-${String(19 + i).padStart(2, '0')}`, weekday: i, capacity: 4,
  items: i < 2 ? [{ trackId: 5, trackTitle: 'Rust', trackType: 'book', count: i + 2, planned: i + 2, done: i === 0 ? 2 : 1 }] : [],
  done: i === 0 ? 2 : i === 1 ? 1 : 0, planned: i < 2 ? i + 2 : 0,
}));

beforeEach(() => { vi.clearAllMocks(); mockWeek.mockResolvedValue(days); });

describe('WeekPlan', () => {
  it('renders 7 day columns with track chips and counts', async () => {
    render(<WeekPlan />);
    await waitFor(() => expect(screen.getAllByTestId('week-day')).toHaveLength(7));
    expect(screen.getByText('Mon')).toBeTruthy();
    expect(screen.getByText('Sun')).toBeTruthy();
    const chips = screen.getAllByText('Rust').filter(el => el.closest('[data-testid="week-day"]'));
    expect(chips).toHaveLength(2);
    expect(mockWeek).toHaveBeenCalledWith('2026-10-19');
  });

  it('shows done/planned badges per item and per day, done state when complete', async () => {
    render(<WeekPlan />);
    await screen.findAllByTestId('week-day');
    const items = screen.getAllByTestId('item-progress').map(e => e.textContent.trim());
    expect(items).toEqual(['✓ 2/2', '1/3']);
    expect(screen.getAllByTestId('day-progress').map(e => e.textContent.trim())).toEqual(['2/2', '1/3']);
  });

  it('clicking a track chip opens the editor drawer', async () => {
    render(<WeekPlan />);
    await screen.findAllByTestId('week-day');
    const chip = screen.getAllByText('Rust').find(el => el.closest('[data-testid="week-day"]'));
    fireEvent.click(chip);
    expect(screen.getByTestId('track-drawer')).toBeTruthy();
    expect(screen.getByLabelText('Track name')).toBeTruthy();
  });

  it('New track opens the create form in the drawer', async () => {
    render(<WeekPlan />);
    await screen.findAllByTestId('week-day');
    fireEvent.click(screen.getByText('+ New track'));
    expect(screen.getByTestId('new-track-form')).toBeTruthy();
  });
});
