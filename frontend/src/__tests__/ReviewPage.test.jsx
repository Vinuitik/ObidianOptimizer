import { render, screen, fireEvent, waitFor, act } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';

vi.mock('../pwa/offlineApi', () => ({
  gradeNoteOffline: vi.fn(),
  fetchNoteContentOffline: vi.fn(),
  isDriveMode: () => false,
  buildAssignmentOffline: vi.fn(),
  submitAttemptOffline: vi.fn(),
  completeAssignmentOffline: vi.fn(),
  flagCardOffline: vi.fn(),
}));
vi.mock('../pwa/useOffline', () => ({ default: () => true }));
vi.mock('../components/molecules/NoteRenderer', () => ({
  default: ({ content }) => <div data-testid="note-body">{content}</div>,
}));

import ReviewPage, { ADVANCE_MS } from '../pages/ReviewPage';
import useStore from '../store/useStore';
import * as api from '../pwa/offlineApi';

const NOTES = [
  { shortName: 'alpha', fullPath: 'alpha.md', track: 'read' },
  { shortName: 'beta',  fullPath: 'beta.md',  track: 'read' },
];

function setup(notes = NOTES) {
  const init = vi.fn(async () => { useStore.setState({ reviewNotes: notes }); });
  useStore.setState({
    isAuthenticated: true, reviewNotes: [], reviewHasMore: false,
    initReviewSession: init,
  });
  return init;
}

beforeEach(() => {
  vi.clearAllMocks();
  api.fetchNoteContentOffline.mockImplementation(async p => `body of ${p}`);
  api.gradeNoteOffline.mockImplementation(async (_p, band) => ({ band, due: '2026-12-01T00:00:00Z' }));
});
afterEach(() => { vi.useRealTimers(); });

describe('ReviewPage flow', () => {
  it('auto-opens the first due item without a click', { timeout: 15000 }, async () => {
    setup();
    render(<ReviewPage />);
    await waitFor(() => expect(screen.getByTestId('note-body').textContent).toBe('body of alpha.md'));
    expect(api.fetchNoteContentOffline).toHaveBeenCalledWith('alpha.md');
    expect(screen.getByTestId('band-buttons')).toBeTruthy();
  });

  it('digit key grades; Enter grades Good', { timeout: 15000 }, async () => {
    setup();
    render(<ReviewPage />);
    await screen.findByTestId('band-buttons');
    fireEvent.keyDown(window, { key: '3' });
    await waitFor(() => expect(api.gradeNoteOffline).toHaveBeenCalledWith('alpha.md', 'EASY'));

    // next note opens automatically; Enter = Good
    await screen.findByText('body of beta.md', {}, { timeout: ADVANCE_MS + 4000 });
    await act(async () => {});   // let the new note's key listener attach
    fireEvent.keyDown(window, { key: 'Enter' });
    await waitFor(() => expect(api.gradeNoteOffline).toHaveBeenCalledWith('beta.md', 'GOOD'));
  });

  it('ignores grade keys while typing in a field', { timeout: 15000 }, async () => {
    setup();
    render(<ReviewPage />);
    await screen.findByTestId('band-buttons');
    const input = document.createElement('input');
    document.body.appendChild(input);
    fireEvent.keyDown(input, { key: '1' });
    expect(api.gradeNoteOffline).not.toHaveBeenCalled();
    input.remove();
  });

  it('auto-advances to the next note ~800ms after grading, showing "Graded"', { timeout: 15000 }, async () => {
    setup();
    render(<ReviewPage />);
    await screen.findByTestId('band-buttons');
    vi.useFakeTimers({ shouldAdvanceTime: true });
    fireEvent.keyDown(window, { key: '2' });
    await screen.findByTestId('inline-graded');
    expect(screen.getByTestId('graded-flash').textContent).toMatch(/Graded Good/);
    expect(screen.getByTestId('note-body').textContent).toBe('body of alpha.md');
    await act(async () => { vi.advanceTimersByTime(ADVANCE_MS + 50); });
    await waitFor(() => expect(screen.getByTestId('note-body').textContent).toBe('body of beta.md'));
  });

  it('shows "All caught up" when the queue is empty', { timeout: 15000 }, async () => {
    setup([]);
    render(<ReviewPage />);
    expect(await screen.findByText('All caught up')).toBeTruthy();
  });

  it('shows a retryable error when the queue fails to load', { timeout: 15000 }, async () => {
    setup();
    useStore.setState({ initReviewSession: vi.fn().mockRejectedValue(new Error('boom')) });
    render(<ReviewPage />);
    expect(await screen.findByRole('alert')).toBeTruthy();
  });
});
