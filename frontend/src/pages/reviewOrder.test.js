import { describe, it, expect } from 'vitest';
import { pickNextInterleaved, advanceHistory } from './reviewOrder';

const mk = (c, r) => [
  ...Array.from({ length: c }, (_, i) => ({ fullPath: `c${String(i).padStart(2, '0')}.md`, track: 'flashcard' })),
  ...Array.from({ length: r }, (_, i) => ({ fullPath: `r${String(i).padStart(2, '0')}.md`, track: 'read' })),
];

// Simulate grading: each pick leaves the queue.
function run(notes, hist = {}) {
  let left = [...notes], done = null, h = hist, out = '';
  while (left.length) {
    const n = pickNextInterleaved(left, done, h);
    out += n.track === 'flashcard' ? 'C' : 'R';
    h = advanceHistory(h, n.track);
    done = n.fullPath;
    left = left.filter(x => x !== n);
  }
  return out;
}

describe('pickNextInterleaved', () => {
  it('2 cards / 8 reads', () => expect(run(mk(2, 8))).toBe('CRRRRCRRRR'));
  it('3/3 alternates', () => expect(run(mk(3, 3))).toBe('CRCRCR'));
  it('degenerate', () => {
    expect(run(mk(0, 4))).toBe('RRRR');
    expect(run(mk(4, 0))).toBe('CCCC');
  });
  it('continues the run after partial progress (1 card + 2 reads done, 1/6 left)', () => {
    expect(run(mk(1, 6), { lastTrack: 'read', run: 2 })).toBe('RRCRRRR');
  });
  it('after a card with 1/8 left', () => {
    expect(run(mk(1, 8), { lastTrack: 'flashcard', run: 0 })).toBe('RRRRCRRRR');
  });
  it('keeps DFS order within a track', () => {
    const notes = [
      { fullPath: 'b.md', track: 'read' }, { fullPath: 'a.md', track: 'read' },
      { fullPath: 'z.md', track: 'flashcard' },
    ];
    expect(pickNextInterleaved(notes, 'a.md', { lastTrack: 'read', run: 1 }).fullPath).toBe('b.md');
  });
  it('null when empty', () => expect(pickNextInterleaved([], null)).toBeNull());
});
