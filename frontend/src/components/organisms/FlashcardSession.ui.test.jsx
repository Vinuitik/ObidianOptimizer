import { render, screen, fireEvent, act } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import FlashcardSession from './FlashcardSession';

vi.mock('../../pwa/offlineApi', () => ({
  buildAssignmentOffline: vi.fn(async () => ({
    id: 'a', variants: {},
    cards: [
      { id: 'c1', type: 'mcq', difficulty: 1, payload: { question: 'Q1?', options: ['a', 'b'], correct: 0 } },
      { id: 'c2', type: 'mcq', difficulty: 1, payload: { question: 'Q2?', options: ['a', 'b'], correct: 1 } },
    ],
  })),
  submitAttemptOffline: vi.fn(), completeAssignmentOffline: vi.fn(),
  flagCardOffline: vi.fn(async () => {}),
}));

// jsdom has no PointerEvent: minimal stand-in carrying pointerType + coordinates.
beforeEach(() => {
  localStorage.clear();
  window.PointerEvent = class extends MouseEvent {
    constructor(t, i = {}) { super(t, i); this.pointerType = i.pointerType; }
  };
});
afterEach(() => vi.useRealTimers());

describe('flag form is per card', () => {
  it('open form on card 1 does not carry to card 2; done message stays on card 1', async () => {
    render(<FlashcardSession notePath="/v/N.md" onClose={() => {}} onReviewNote={() => {}} />);
    fireEvent.click(await screen.findByTestId('flag-btn'));
    fireEvent.change(screen.getByTestId('flag-reason'), { target: { value: 'bad' } });
    fireEvent.click(screen.getByTestId('next-btn'));
    expect(screen.getByTestId('flag-btn')).toBeInTheDocument();
    expect(screen.queryByTestId('flag-box')).toBeNull();

    fireEvent.click(screen.getByText('← Prev'));
    fireEvent.click(screen.getByTestId('flag-btn'));
    await act(async () => { fireEvent.click(screen.getByTestId('flag-confirm')); });
    expect(screen.getByTestId('flag-done')).toBeInTheDocument();
    fireEvent.click(screen.getByTestId('next-btn'));
    expect(screen.queryByTestId('flag-done')).toBeNull();
  });
});

describe('MCQ option needs a clean tap', () => {
  async function setup() {
    render(<FlashcardSession notePath="/v/N.md" onClose={() => {}} onReviewNote={() => {}} />);
    return screen.findByTestId('option-0');
  }
  const press = (el, type, extra = {}) =>
    fireEvent(el, new window.PointerEvent(type, { bubbles: true, pointerType: 'touch', clientX: 10, clientY: 10, ...extra }));

  it('quick tap selects', async () => {
    const el = await setup();
    vi.useFakeTimers({ toFake: ['Date'] });
    press(el, 'pointerdown'); vi.advanceTimersByTime(100); press(el, 'pointerup'); fireEvent.click(el);
    expect(screen.getByTestId('answered-badge')).toBeInTheDocument();
  });

  it('long press does not select', async () => {
    const el = await setup();
    vi.useFakeTimers({ toFake: ['Date'] });
    press(el, 'pointerdown'); vi.advanceTimersByTime(700); press(el, 'pointerup'); fireEvent.click(el);
    expect(screen.queryByTestId('answered-badge')).toBeNull();
    press(el, 'pointerdown'); press(el, 'pointerup'); fireEvent.click(el); // next clean tap works
    expect(screen.getByTestId('answered-badge')).toBeInTheDocument();
  });

  it('moved press (scroll) does not select', async () => {
    const el = await setup();
    press(el, 'pointerdown'); press(el, 'pointermove', { clientY: 40 }); press(el, 'pointerup'); fireEvent.click(el);
    expect(screen.queryByTestId('answered-badge')).toBeNull();
  });

  it('keyboard / plain click still selects', async () => {
    const el = await setup();
    fireEvent.click(el);
    expect(screen.getByTestId('answered-badge')).toBeInTheDocument();
  });
});

describe('result display for open cards', () => {
  async function runTo(results, cardIds = ['c1', 'c2']) {
    const api = await import('../../pwa/offlineApi');
    api.buildAssignmentOffline.mockResolvedValueOnce({
      id: 'a2', variants: {},
      cards: cardIds.map(id => ({ id, type: 'open', difficulty: 2,
        payload: { question: `Q-${id}`, reference_answers: ['local ref'] } })),
    });
    api.submitAttemptOffline.mockImplementation(async (_a, cid) => results[cid]);
    api.completeAssignmentOffline.mockResolvedValue({ notes: [] });
    render(<FlashcardSession notePath="/v/O.md" onClose={() => {}} onReviewNote={() => {}} />);
    await screen.findByTestId('flashcard-session');
    for (let i = 0; i < cardIds.length; i++) {
      await act(async () => { fireEvent.click(screen.getByTestId('next-btn')); });
    }
    await screen.findByTestId('flashcard-result');
  }

  it('provisional: reference + quiet mark; non-provisional: feedback, no mark', async () => {
    await runTo({ c1: { verdict: 'PARTIAL', provisional: true, referenceAnswer: 'srv ref', pointsEarned: 1, maxPoints: 2, feedback: null },
                  c2: { verdict: 'CORRECT', provisional: false, referenceAnswer: 'r2', pointsEarned: 2, maxPoints: 2, feedback: 'nice' } });
    expect(screen.getByTestId('reference-c1')).toHaveTextContent('srv ref');
    expect(screen.getByTestId('provisional-c1')).toHaveTextContent('Provisional');
    expect(screen.queryByTestId('provisional-c2')).toBeNull();
    expect(screen.getByTestId('reference-c2')).toHaveTextContent('r2');
    expect(screen.getByText('nice')).toBeInTheDocument();
  });

  it('results render in received card order', async () => {
    const w = { verdict: 'WRONG', provisional: false, referenceAnswer: 'x', pointsEarned: 0, maxPoints: 2 };
    await runTo({ c9: w, c3: w, c5: w }, ['c9', 'c3', 'c5']);
    const ids = screen.getAllByTestId(/^result-card-/).map(e => e.dataset.testid);
    expect(ids).toEqual(['result-card-c9', 'result-card-c3', 'result-card-c5']);
  });
});
