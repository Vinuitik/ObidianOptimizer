import { render, screen, fireEvent } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import ReviewPage from './ReviewPage';
import useStore from '../store/useStore';

vi.mock('../pwa/offlineApi', () => ({
  isDriveMode: () => true,
  buildAssignmentOffline: vi.fn(async () => ({
    id: 'asg-1', variants: {},
    cards: [
      { id: 'c1', type: 'mcq', difficulty: 1, payload: { question: 'Q1?', options: ['a', 'b'], correct: 0 } },
      { id: 'c2', type: 'mcq', difficulty: 1, payload: { question: 'Q2?', options: ['a', 'b'], correct: 1 } },
    ],
  })),
  submitAttemptOffline: vi.fn(async () => ({ verdict: 'CORRECT', pointsEarned: 1, maxPoints: 1, feedback: '' })),
  completeAssignmentOffline: vi.fn(async () => ({ deferred: true, notes: [] })),
  flagCardOffline: vi.fn(), gradeNoteOffline: vi.fn(),
  fetchNoteContentOffline: vi.fn(async () => '# note'),
}));
import * as api from '../pwa/offlineApi';

const NOTE = { shortName: 'N', fullPath: '/v/N.md', track: 'flashcard', hasCards: true };
const initReviewSession = vi.fn(async () => {});

beforeEach(() => {
  localStorage.clear();
  vi.clearAllMocks();
  useStore.setState({
    isAuthenticated: true, reviewNotes: [NOTE], reviewHasMore: false, initReviewSession,
    settings: { ...useStore.getState().settings, flashcardsEnabled: true },
  });
});

describe('flashcard session survives ReviewPage unmount (tab switch)', () => {
  it('resumes same card, same buffered answers, without rebuilding', async () => {
    const { unmount } = render(<ReviewPage />);
    fireEvent.click(await screen.findByTestId('option-1'));
    fireEvent.click(screen.getByTestId('next-btn'));
    expect(screen.getByText('2 / 2')).toBeInTheDocument();
    unmount();

    useStore.setState({ reviewNotes: [] }); // persisted session decides, not the list
    render(<ReviewPage />);
    expect(await screen.findByText('2 / 2')).toBeInTheDocument();
    expect(api.buildAssignmentOffline).toHaveBeenCalledTimes(1);
    fireEvent.click(screen.getByText('← Prev'));
    expect(screen.getByText('1 / 2')).toBeInTheDocument();
    expect(screen.getByTestId('answered-badge')).toBeInTheDocument();
  });

  it('after finishing, remount shows the result (no re-submit) and Next note clears it', async () => {
    const { unmount } = render(<ReviewPage />);
    fireEvent.click(await screen.findByTestId('next-btn'));
    fireEvent.click(screen.getByTestId('next-btn'));
    await screen.findByTestId('flashcard-result');
    expect(api.submitAttemptOffline).toHaveBeenCalledTimes(2);
    expect(api.completeAssignmentOffline).toHaveBeenCalledTimes(1);
    unmount();

    render(<ReviewPage />);
    expect(await screen.findByTestId('flashcard-result')).toBeInTheDocument();
    expect(api.submitAttemptOffline).toHaveBeenCalledTimes(2);
    expect(api.completeAssignmentOffline).toHaveBeenCalledTimes(1);
    expect(api.buildAssignmentOffline).toHaveBeenCalledTimes(1);

    fireEvent.click(screen.getByTestId('next-note-btn'));
    expect(localStorage.getItem('obsOpt_flashSession')).toBeNull();
  });

  it('double Finish submits once', async () => {
    render(<ReviewPage />);
    await screen.findByTestId('next-btn');
    fireEvent.click(screen.getByTestId('next-btn'));
    const finish = screen.getByTestId('next-btn');
    fireEvent.click(finish); fireEvent.click(finish);
    await screen.findByTestId('flashcard-result');
    expect(api.completeAssignmentOffline).toHaveBeenCalledTimes(1);
    expect(api.submitAttemptOffline).toHaveBeenCalledTimes(2);
  });

  it('without persisted state a remount is blank (the original bug)', async () => {
    const { unmount } = render(<ReviewPage />);
    fireEvent.click(await screen.findByTestId('option-1'));
    unmount();
    localStorage.clear(); // = pre-fix behaviour: all session state was component-local
    useStore.setState({ reviewNotes: [] }); // note was dismissed after finish / list reloaded
    render(<ReviewPage />);
    // no activeNote + empty queue -> "All caught up", no quiz at all
    expect(await screen.findByText('All caught up')).toBeInTheDocument();
    expect(screen.queryByTestId('flashcard-session')).toBeNull();
  });
});

describe('completed notes are not re-offered before sync', () => {
  it('completeReview records the note as done today', async () => {
    const { doneNotePaths } = await import('../store/flashSessionPersist');
    await useStore.getState().completeReview('/v/N.md', async () => ({}));
    expect(doneNotePaths().has('/v/N.md')).toBe(true);
  });
});
