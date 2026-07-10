import { describe, it, expect } from 'vitest';
import { allocateTracks } from '../pwa/reviewPlan';

// Helper: N due notes, oldest-first, marking which have cards.
const note = (i, hasCards) => ({ path: `/n${i}.md`, hasCards });
const tracksOf = (out) => out.map(n => n.track);

describe('allocateTracks — hybrid review split', () => {
  it('gives flashcard slots to card-bearing notes (oldest first), rest read', () => {
    const notes = [note(1, true), note(2, true), note(3, false), note(4, true)];
    const out = allocateTracks(notes, { flashcardMax: 2, totalMax: 50 });
    // First two card-bearing → flashcard; the no-cards note and the 3rd card note → read.
    expect(tracksOf(out)).toEqual(['flashcard', 'flashcard', 'read', 'read']);
  });

  it('never puts a no-cards note on the flashcard track', () => {
    const notes = [note(1, false), note(2, false), note(3, true)];
    const out = allocateTracks(notes, { flashcardMax: 5, totalMax: 50 });
    expect(tracksOf(out)).toEqual(['read', 'read', 'flashcard']);
  });

  it('honors the total daily ceiling (drops the overflow entirely)', () => {
    const notes = Array.from({ length: 10 }, (_, i) => note(i, true));
    const out = allocateTracks(notes, { flashcardMax: 20, totalMax: 3 });
    expect(out).toHaveLength(3);
    expect(tracksOf(out)).toEqual(['flashcard', 'flashcard', 'flashcard']);
  });

  it('shrinks the flashcard budget by what was already done today', () => {
    const notes = [note(1, true), note(2, true), note(3, true)];
    // 20 cap but 19 already done → only 1 slot left; rest fall to read.
    const out = allocateTracks(notes, { flashcardMax: 20, totalMax: 50, flashcardsDoneToday: 19 });
    expect(tracksOf(out)).toEqual(['flashcard', 'read', 'read']);
  });

  it('flashcardMax 0 (or budget spent) makes the whole day read-track', () => {
    const notes = [note(1, true), note(2, true)];
    expect(tracksOf(allocateTracks(notes, { flashcardMax: 0, totalMax: 50 }))).toEqual(['read', 'read']);
    expect(tracksOf(allocateTracks(notes, { flashcardMax: 5, totalMax: 50, flashcardsDoneToday: 5 })))
      .toEqual(['read', 'read']);
  });

  it('carryover is emergent: an aged (earlier) card-bearing note wins the slot', () => {
    // Yesterday's skipped note sorts first (oldest); with 1 slot it takes flashcard,
    // a newer card-bearing note behind it is bumped to read.
    const notes = [note('old', true), note('new', true)];
    const out = allocateTracks(notes, { flashcardMax: 1, totalMax: 50 });
    expect(out[0].path).toBe('/nold.md');
    expect(tracksOf(out)).toEqual(['flashcard', 'read']);
  });

  it('empty due list → empty plan', () => {
    expect(allocateTracks([], { flashcardMax: 20, totalMax: 50 })).toEqual([]);
  });
});
