import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';

vi.mock('../api/notes', () => ({
  buildAssignment:    vi.fn(),
  submitAttempt:      vi.fn(),
  completeAssignment: vi.fn(),
}));

import FlashcardSession from '../components/organisms/FlashcardSession';
import * as api from '../api/notes';

const noop = () => {};

const ASSIGNMENT = {
  id: 'a1',
  scope: '/vault/note.md',
  targetPoints: 10,
  actualPoints: 5,
  cards: [
    { id: 'c1', type: 'mcq', difficulty: 2,
      payload: { question: 'What is FSRS?', options: ['Scheduler', 'Database', 'Editor'], correct: 0 } },
    { id: 'c2', type: 'open', difficulty: 3,
      payload: { question: 'Explain stability.' } },
  ],
  variants: {},
};

const COMPLETION = {
  assignmentId: 'a1',
  notes: [{ notePath: '/vault/note.md', score: 0.8, band: 'EASY', due: '2026-06-20T10:00:00Z' }],
};

beforeEach(() => {
  vi.clearAllMocks();
  api.buildAssignment.mockResolvedValue(ASSIGNMENT);
  api.submitAttempt.mockResolvedValue({ verdict: 'CORRECT', pointsEarned: 2, maxPoints: 2 });
  api.completeAssignment.mockResolvedValue(COMPLETION);
});

async function renderSession(props = {}) {
  render(<FlashcardSession notePath="/vault/note.md" onReviewNote={noop} onClose={noop} {...props} />);
  await waitFor(() => expect(screen.getByTestId('flashcard-session')).toBeTruthy());
}

describe('FlashcardSession — loading', () => {
  it('builds an assignment for the note on mount', async () => {
    await renderSession();
    expect(api.buildAssignment).toHaveBeenCalledWith('/vault/note.md', 10);
    expect(screen.getByText('What is FSRS?')).toBeTruthy();
  });

  it('shows error state with note fallback when build fails', async () => {
    api.buildAssignment.mockRejectedValue(new Error('no active cards in scope'));
    const onReviewNote = vi.fn();
    render(<FlashcardSession notePath="/vault/note.md" onReviewNote={onReviewNote} onClose={noop} />);
    await waitFor(() => expect(screen.getByTestId('flashcard-error')).toBeTruthy());
    fireEvent.click(screen.getByRole('button', { name: /review note directly/i }));
    // no-cards fallback: the inline note IS the grading surface → canGrade=true
    expect(onReviewNote).toHaveBeenCalledWith('/vault/note.md', true);
  });
});

describe('FlashcardSession — quiz phase (answers stay editable, nothing sent until Finish)', () => {
  it('selecting an MCQ option does not call the server or lock the card', async () => {
    await renderSession();
    fireEvent.click(screen.getByTestId('option-0'));
    expect(api.submitAttempt).not.toHaveBeenCalled();
    // no submit button, no lock — the option buttons stay enabled
    expect(screen.queryByTestId('mcq-submit')).toBeNull();
    expect(screen.getByTestId('option-0')).not.toBeDisabled();
    expect(screen.getByTestId('next-btn')).not.toBeDisabled();
  });

  it('lets the student change an MCQ pick freely, including after navigating away and back', async () => {
    await renderSession();
    fireEvent.click(screen.getByTestId('option-0'));
    fireEvent.click(screen.getByTestId('option-2'));   // changed mind
    expect(screen.getByTestId('option-2').className).toMatch(/optionChosen|chosen/i);

    fireEvent.click(screen.getByTestId('next-btn'));   // → card 2 (open)
    fireEvent.click(screen.getByRole('button', { name: /prev/i }));   // back → card 1
    // the earlier pick (option 2) is still selected, and can still be changed
    expect(screen.getByTestId('option-2').className).toMatch(/optionChosen|chosen/i);
    fireEvent.click(screen.getByTestId('option-1'));
    expect(screen.getByTestId('option-1').className).toMatch(/optionChosen|chosen/i);
    expect(api.submitAttempt).not.toHaveBeenCalled();
  });

  it('shows an answered checkmark without locking the input', async () => {
    await renderSession();
    expect(screen.queryByTestId('answered-badge')).toBeNull();
    fireEvent.click(screen.getByTestId('option-0'));
    expect(screen.getByTestId('answered-badge')).toBeTruthy();
    expect(screen.getByTestId('answered-note').textContent).toMatch(/Answered/);
    expect(api.submitAttempt).not.toHaveBeenCalled();
  });

  it('does NOT reveal correctness during the quiz (exam style)', async () => {
    await renderSession();
    fireEvent.click(screen.getByTestId('option-0'));
    // no per-card verdict leak, and the correct option is not highlighted
    expect(screen.queryByTestId('verdict')).toBeNull();
    expect(screen.getByTestId('option-0').className).not.toMatch(/correct/i);
  });

  it('"I don\'t know" buffers a mark without calling the server', async () => {
    await renderSession();
    fireEvent.click(screen.getByTestId('idk-btn'));
    expect(api.submitAttempt).not.toHaveBeenCalled();
    expect(screen.getByTestId('answered-note').textContent).toMatch(/I don.t know/);
    expect(screen.getByTestId('next-btn')).not.toBeDisabled();
  });

  it('open-ended answers just update local state, no per-card call', async () => {
    await renderSession();
    fireEvent.click(screen.getByTestId('option-0'));
    fireEvent.click(screen.getByTestId('next-btn'));

    fireEvent.change(screen.getByTestId('open-textarea'), { target: { value: 'memory strength' } });
    expect(api.submitAttempt).not.toHaveBeenCalled();
    expect(screen.queryByRole('button', { name: /submit answer/i })).toBeNull();
  });
});

describe('FlashcardSession — Finish (batch grading)', () => {
  async function completeSession() {
    await renderSession();
    fireEvent.click(screen.getByTestId('option-0'));
    fireEvent.click(screen.getByTestId('next-btn'));
    fireEvent.change(screen.getByTestId('open-textarea'), { target: { value: 'answer' } });
    fireEvent.click(screen.getByTestId('next-btn'));           // last card → Finish
    await waitFor(() => expect(screen.getByTestId('flashcard-result')).toBeTruthy());
  }

  it('submits every buffered answer, then completes the assignment', async () => {
    await completeSession();
    expect(api.submitAttempt).toHaveBeenCalledTimes(2);
    expect(api.submitAttempt).toHaveBeenCalledWith('a1', 'c1', '0');
    expect(api.submitAttempt).toHaveBeenCalledWith('a1', 'c2', 'answer');
    // all attempts must land before completion is requested
    expect(api.completeAssignment).toHaveBeenCalledWith('a1');
    expect(screen.getByTestId('band-result').textContent).toMatch(/EASY/);
  });

  it('shows per-card verdict breakdown from the batch responses', async () => {
    await completeSession();
    expect(screen.getByTestId('result-card-c1').className).toMatch(/correct/i);
    expect(screen.getByTestId('result-card-c2').className).toMatch(/correct/i);
  });

  it('unanswered cards are sent as empty (same as "I don\'t know") rather than skipped', async () => {
    await renderSession();
    // never touch card 1, just page through and finish
    fireEvent.click(screen.getByTestId('next-btn'));
    fireEvent.click(screen.getByTestId('next-btn'));           // last card → Finish
    await waitFor(() => expect(screen.getByTestId('flashcard-result')).toBeTruthy());
    expect(api.submitAttempt).toHaveBeenCalledWith('a1', 'c1', '');
    expect(api.submitAttempt).toHaveBeenCalledWith('a1', 'c2', '');
  });

  it('reveals the correct answer at the end for cards answered wrong', async () => {
    api.submitAttempt.mockResolvedValue({ verdict: 'WRONG', pointsEarned: 0, maxPoints: 2 });
    await renderSession();
    fireEvent.click(screen.getByTestId('option-2'));            // wrong (correct is 0)
    fireEvent.click(screen.getByTestId('next-btn'));
    fireEvent.click(screen.getByTestId('idk-btn'));              // open card → "I don't know"
    fireEvent.click(screen.getByTestId('next-btn'));
    await waitFor(() => screen.getByTestId('flashcard-result'));
    // mcq correct option text is revealed only now
    expect(screen.getByTestId('result-card-c1').textContent).toMatch(/Correct answer: Scheduler/);
  });

  it('review note button navigates to the note', async () => {
    const onReviewNote = vi.fn();
    render(<FlashcardSession notePath="/vault/note.md" onReviewNote={onReviewNote} onClose={noop} />);
    await waitFor(() => screen.getByTestId('flashcard-session'));
    fireEvent.click(screen.getByTestId('option-0'));
    fireEvent.click(screen.getByTestId('next-btn'));
    fireEvent.change(screen.getByTestId('open-textarea'), { target: { value: 'a' } });
    fireEvent.click(screen.getByTestId('next-btn'));
    await waitFor(() => screen.getByTestId('flashcard-result'));

    fireEvent.click(screen.getByRole('button', { name: /review note directly/i }));
    expect(onReviewNote).toHaveBeenCalledWith('/vault/note.md');
  });
});

describe('FlashcardSession — exercise cards', () => {
  it('renders the frozen variant and buffers the typed answer until Finish', async () => {
    api.buildAssignment.mockResolvedValue({
      ...ASSIGNMENT,
      cards: [{ id: 'e1', type: 'exercise', difficulty: 3,
                payload: { template: 'Comparisons for {n}?', answer_kind: 'numeric' } }],
      variants: { e1: { rendered: 'Comparisons for 5?', params: { n: 5 }, expected: 10 } },
    });
    await renderSession();
    expect(screen.getByText('Comparisons for 5?')).toBeTruthy();
    fireEvent.change(screen.getByTestId('exercise-input'), { target: { value: '10' } });
    expect(api.submitAttempt).not.toHaveBeenCalled();
    fireEvent.click(screen.getByTestId('next-btn'));            // only card → Finish
    await waitFor(() => expect(api.submitAttempt).toHaveBeenCalledWith('a1', 'e1', '10'));
  });
});
