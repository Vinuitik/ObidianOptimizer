import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import TracksPage from './TracksPage';

const fn = () => vi.fn().mockResolvedValue([]);
const state = {
  isAuthenticated: true,
  tracks: [{ id: 5, title: 'Existing', type: 'book', status: 'active', deadline: null, includeInProgress: false }],
  trackItems: {}, trackSchedules: {}, trackCapacity: {}, trackProgress: [],
  todayItems: [], todayMode: 'normal', todayOverBudget: false,
  trackPending: [], addTrackPending: vi.fn(), updateSchedule: vi.fn(),
  showToast: vi.fn(),
  createTrack: vi.fn(), updateTrack: vi.fn(), addTrackItem: vi.fn(),
  fetchTracks: fn(), fetchTrackItems: fn(), fetchTrackCapacity: fn(),
  fetchTrackSchedule: fn(), fetchTodayPlan: fn(), fetchTrackProgress: fn(),
};
vi.mock('../store/useStore', () => ({ default: vi.fn(sel => sel(state)) }));

const mockCapture = vi.fn();
vi.mock('../api/capture', () => ({
  captureResource: (...a) => mockCapture(...a),
  captureFile: vi.fn(),
}));
vi.mock('../api/notes', () => ({ searchNotes: vi.fn().mockResolvedValue([]) }));
vi.mock('../api/tracks', () => ({
  generateMinicourse: vi.fn(), fetchMinicourseJob: vi.fn(), approveMinicourse: vi.fn(),
  importCsv: vi.fn(), commitImport: vi.fn(),
}));

function openManage() {
  render(<TracksPage />);
  fireEvent.click(screen.getByText('Manage tracks'));
}

beforeEach(() => {
  vi.clearAllMocks();
  state.createTrack.mockResolvedValue({ id: 9, title: 'T' });
  state.updateTrack.mockResolvedValue({});
  state.updateSchedule.mockResolvedValue({});
  state.addTrackItem.mockResolvedValue({});
  mockCapture.mockResolvedValue({ ok: true });
});

describe('NewTrackForm (Manage tab)', () => {
  it('creates with title only', async () => {
    openManage();
    fireEvent.change(screen.getByLabelText('Track title'), { target: { value: 'Rust' } });
    fireEvent.click(screen.getByText('+ New track'));
    await waitFor(() => expect(state.createTrack).toHaveBeenCalledWith('Rust', 'book'));
    expect(mockCapture).not.toHaveBeenCalled();
    expect(state.updateTrack).not.toHaveBeenCalled();
    // no deadline -> default schedule 1/day, all 7 days
    expect(state.updateSchedule).toHaveBeenCalledWith(9, { 0: 1, 1: 1, 2: 1, 3: 1, 4: 1, 5: 1, 6: 1 });
  });

  it('create with 3 links -> captures, queues processing rows, switches to Today', async () => {
    openManage();
    fireEvent.change(screen.getByLabelText('Track title'), { target: { value: 'Rust' } });
    fireEvent.change(screen.getByLabelText('Links or items'), { target: { value: 'https://a.com\nhttps://b.com\nhttps://c.com' } });
    fireEvent.click(screen.getByText('+ New track'));
    await waitFor(() => expect(mockCapture).toHaveBeenCalledTimes(3));
    await waitFor(() => expect(state.addTrackPending).toHaveBeenCalled());
    expect(state.addTrackPending.mock.calls[0][0]).toHaveLength(3);
    expect(state.updateSchedule).toHaveBeenCalledTimes(1);
    await waitFor(() => expect(screen.queryByLabelText('Track title')).toBeNull());
    expect(state.fetchTodayPlan).toHaveBeenCalled();
  });

  it('title required', () => {
    openManage();
    expect(screen.getByText('+ New track')).toBeDisabled();
  });

  it('title + urls + deadline creates track then captures each url', async () => {
    openManage();
    fireEvent.change(screen.getByLabelText('Track title'), { target: { value: 'Rust' } });
    fireEvent.change(screen.getByLabelText('Links or items'), { target: { value: 'https://a.com\nhttps://b.com' } });
    fireEvent.change(screen.getByLabelText(/Deadline \(optional\)/), { target: { value: '2026-12-01' } });
    fireEvent.click(screen.getByText('+ New track'));
    await waitFor(() => expect(mockCapture).toHaveBeenCalledTimes(2));
    expect(mockCapture).toHaveBeenCalledWith('https://a.com', 9);
    expect(state.updateTrack).toHaveBeenCalledWith(9, { deadline: '2026-12-01' });
    expect(state.updateSchedule).not.toHaveBeenCalled();
  });

  it('plain text lines become plain items', async () => {
    openManage();
    fireEvent.change(screen.getByLabelText('Track title'), { target: { value: 'Rust' } });
    fireEvent.change(screen.getByLabelText('Links or items'), { target: { value: 'ch 1\nch 2' } });
    fireEvent.click(screen.getByText('+ New track'));
    await waitFor(() => expect(state.addTrackItem).toHaveBeenCalledTimes(2));
    expect(state.addTrackItem).toHaveBeenCalledWith(9, 'ch 1', null);
  });
});

describe('Track detail Advanced disclosure', () => {
  it('is collapsed by default and holds the secondary settings', () => {
    openManage();
    fireEvent.click(screen.getByText('Existing'));
    const btn = screen.getAllByText('Advanced')[1].closest('button');
    expect(btn.getAttribute('aria-expanded')).toBe('false');
    expect(screen.queryByText('Show on Progress tab')).toBeNull();
    fireEvent.click(btn);
    expect(screen.getByText('Show on Progress tab')).toBeTruthy();
    expect(screen.getByText('Generate a course')).toBeTruthy();
    expect(screen.getByText('Weekly schedule')).toBeTruthy();
  });
});
