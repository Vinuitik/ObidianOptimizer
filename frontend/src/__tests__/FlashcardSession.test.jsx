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

describe('FlashcardSession — quiz phase', () => {
  it('Next is disabled until the answer is verified by the server', async () => {
    await renderSession();
    expect(screen.getByTestId('next-btn')).toBeDisabled();
    fireEvent.click(screen.getByTestId('option-0'));
    await waitFor(() => expect(screen.getByTestId('next-btn')).not.toBeDisabled());
    expect(api.submitAttempt).toHaveBeenCalledWith('a1', 'c1', '0');
  });

  it('does NOT reveal correctness during the quiz (exam style)', async () => {
    await renderSession();
    fireEvent.click(screen.getByTestId('option-0'));
    await waitFor(() => screen.getByTestId('recorded'));
    // no per-card verdict leak, and the correct option is not highlighted
    expect(screen.queryByTestId('verdict')).toBeNull();
    expect(screen.getByTestId('option-0').className).not.toMatch(/correct/i);
    expect(screen.getByTestId('recorded').textContent).toMatch(/Answer recorded/);
  });

  it('"I don\'t know" records a WRONG attempt (empty answer), not a skip', async () => {
    api.submitAttempt.mockResolvedValue({ verdict: 'WRONG', pointsEarned: 0, maxPoints: 2 });
    await renderSession();
    fireEvent.click(screen.getByTestId('idk-btn'));
    await waitFor(() => expect(screen.getByTestId('next-btn')).not.toBeDisabled());
    expect(api.submitAttempt).toHaveBeenCalledWith('a1', 'c1', '');
    expect(screen.getByTestId('recorded').textContent).toMatch(/I don.t know/);
  });

  it('open-ended answers go to the judge', async () => {
    await renderSession();
    fireEvent.click(screen.getByTestId('option-0'));
    await waitFor(() => screen.getByTestId('recorded'));
    fireEvent.click(screen.getByTestId('next-btn'));

    fireEvent.change(screen.getByTestId('open-textarea'), { target: { value: 'memory strength' } });
    fireEvent.click(screen.getByRole('button', { name: /submit answer/i }));
    await waitFor(() => screen.getByTestId('recorded'));
    expect(api.submitAttempt).toHaveBeenLastCalledWith('a1', 'c2', 'memory strength');
  });
});

describe('FlashcardSession — result phase', () => {
  async function completeSession() {
    await renderSession();
    fireEvent.click(screen.getByTestId('option-0'));
    await waitFor(() => screen.getByTestId('recorded'));
    fireEvent.click(screen.getByTestId('next-btn'));
    fireEvent.change(screen.getByTestId('open-textarea'), { target: { value: 'answer' } });
    fireEvent.click(screen.getByRole('button', { name: /submit answer/i }));
    await waitFor(() => screen.getByTestId('recorded'));
    fireEvent.click(screen.getByTestId('next-btn'));
    await waitFor(() => expect(screen.getByTestId('flashcard-result')).toBeTruthy());
  }

  it('completes the assignment and shows the FSRS band', async () => {
    await completeSession();
    expect(api.completeAssignment).toHaveBeenCalledWith('a1');
    expect(screen.getByTestId('band-result').textContent).toMatch(/EASY/);
  });

  it('shows per-card verdict breakdown', async () => {
    await completeSession();
    expect(screen.getByTestId('result-card-c1').className).toMatch(/correct/i);
    expect(screen.getByTestId('result-card-c2').className).toMatch(/correct/i);
  });

  it('reveals the correct answer at the end for cards answered wrong', async () => {
    api.submitAttempt.mockResolvedValue({ verdict: 'WRONG', pointsEarned: 0, maxPoints: 2 });
    await renderSession();
    fireEvent.click(screen.getByTestId('option-2'));            // wrong (correct is 0)
    await waitFor(() => screen.getByTestId('recorded'));
    fireEvent.click(screen.getByTestId('next-btn'));
    fireEvent.click(screen.getByTestId('idk-btn'));             // open card → "I don't know"
    await waitFor(() => screen.getByTestId('recorded'));
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
    await waitFor(() => screen.getByTestId('recorded'));
    fireEvent.click(screen.getByTestId('next-btn'));
    fireEvent.change(screen.getByTestId('open-textarea'), { target: { value: 'a' } });
    fireEvent.click(screen.getByRole('button', { name: /submit answer/i }));
    await waitFor(() => screen.getByTestId('recorded'));
    fireEvent.click(screen.getByTestId('next-btn'));
    await waitFor(() => screen.getByTestId('flashcard-result'));

    fireEvent.click(screen.getByRole('button', { name: /review note directly/i }));
    expect(onReviewNote).toHaveBeenCalledWith('/vault/note.md');
  });
});

describe('FlashcardSession — exercise cards', () => {
  it('renders the frozen variant and submits the typed answer', async () => {
    api.buildAssignment.mockResolvedValue({
      ...ASSIGNMENT,
      cards: [{ id: 'e1', type: 'exercise', difficulty: 3,
                payload: { template: 'Comparisons for {n}?', answer_kind: 'numeric' } }],
      variants: { e1: { rendered: 'Comparisons for 5?', params: { n: 5 }, expected: 10 } },
    });
    await renderSession();
    expect(screen.getByText('Comparisons for 5?')).toBeTruthy();
    fireEvent.change(screen.getByTestId('exercise-input'), { target: { value: '10' } });
    fireEvent.click(screen.getByRole('button', { name: /submit answer/i }));
    await waitFor(() => expect(api.submitAttempt).toHaveBeenCalledWith('a1', 'e1', '10'));
  });
});
