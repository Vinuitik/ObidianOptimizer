import { render, screen, fireEvent } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import FlashcardSession from '../components/organisms/FlashcardSession';

const noop = () => {};

describe('FlashcardSession — quiz phase', () => {
  it('renders the first card on mount', () => {
    render(<FlashcardSession notePath="/vault/note.md" onReviewNote={noop} onClose={noop} />);
    expect(screen.getByTestId('flashcard-card')).toBeTruthy();
    expect(screen.getByTestId('flashcard-session')).toBeTruthy();
  });

  it('Next button is disabled until an answer is selected', () => {
    render(<FlashcardSession notePath="/vault/note.md" onReviewNote={noop} onClose={noop} />);
    expect(screen.getByTestId('next-btn')).toBeDisabled();
  });

  it('selecting a MCQ option locks that card and enables Next', () => {
    render(<FlashcardSession notePath="/vault/note.md" onReviewNote={noop} onClose={noop} />);
    fireEvent.click(screen.getByTestId('option-1'));
    expect(screen.getByTestId('next-btn')).not.toBeDisabled();
    // All options should be disabled after selection
    for (let i = 0; i < 4; i++) {
      expect(screen.getByTestId(`option-${i}`)).toBeDisabled();
    }
  });

  it('navigates to next card on Next click', () => {
    render(<FlashcardSession notePath="/vault/note.md" onReviewNote={noop} onClose={noop} />);
    fireEvent.click(screen.getByTestId('option-0'));
    fireEvent.click(screen.getByTestId('next-btn'));
    // Second card is open-ended — should show textarea
    expect(screen.getByTestId('open-textarea')).toBeTruthy();
  });
});

describe('FlashcardSession — result phase', () => {
  function completeSession() {
    render(<FlashcardSession notePath="/vault/note.md" onReviewNote={noop} onClose={noop} />);

    // Card 1: MCQ — select correct answer (index 0)
    fireEvent.click(screen.getByTestId('option-0'));
    fireEvent.click(screen.getByTestId('next-btn'));

    // Card 2: open-ended — type and submit
    fireEvent.change(screen.getByTestId('open-textarea'), { target: { value: 'My answer' } });
    fireEvent.click(screen.getByRole('button', { name: /submit answer/i }));
    fireEvent.click(screen.getByTestId('next-btn'));

    // Card 3: MCQ — select wrong answer (index 1, correct is 2)
    fireEvent.click(screen.getByTestId('option-1'));
    fireEvent.click(screen.getByTestId('next-btn'));
  }

  it('shows result screen after all cards answered', () => {
    completeSession();
    expect(screen.getByTestId('flashcard-result')).toBeTruthy();
  });

  it('marks MCQ correct answer green', () => {
    completeSession();
    expect(screen.getByTestId('result-card-c1').className).toMatch(/correct/i);
  });

  it('marks MCQ wrong answer red', () => {
    completeSession();
    expect(screen.getByTestId('result-card-c3').className).toMatch(/wrong/i);
  });

  it('open-ended card shows self-mark buttons', () => {
    completeSession();
    expect(screen.getByRole('button', { name: /yes ✓/i })).toBeTruthy();
    expect(screen.getByRole('button', { name: /no ✗/i })).toBeTruthy();
  });

  it('self-marking open-ended as correct updates score display', () => {
    completeSession();
    // Before self-mark: score is 1/3 (only first MCQ correct, last MCQ wrong)
    // c2 pending, c3 wrong → autoScore=1, selfCorrect=0 → 1/3
    expect(screen.getByText('1')).toBeTruthy(); // scoreBig

    fireEvent.click(screen.getByRole('button', { name: /yes/i }));
    // After: 2/3
    expect(screen.getByText('2')).toBeTruthy();
  });

  it('calls onReviewNote with notePath when Review Note button clicked', () => {
    const onReviewNote = vi.fn();
    render(<FlashcardSession notePath="/vault/note.md" onReviewNote={onReviewNote} onClose={noop} />);

    fireEvent.click(screen.getByTestId('option-0'));
    fireEvent.click(screen.getByTestId('next-btn'));
    fireEvent.change(screen.getByTestId('open-textarea'), { target: { value: 'answer' } });
    fireEvent.click(screen.getByRole('button', { name: /submit answer/i }));
    fireEvent.click(screen.getByTestId('next-btn'));
    fireEvent.click(screen.getByTestId('option-1'));
    fireEvent.click(screen.getByTestId('next-btn'));

    fireEvent.click(screen.getByRole('button', { name: /review note/i }));
    expect(onReviewNote).toHaveBeenCalledWith('/vault/note.md');
  });
});
