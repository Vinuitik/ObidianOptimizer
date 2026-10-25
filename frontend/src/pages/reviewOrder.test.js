import { describe, it, expect } from 'vitest';
import { orderQueue, pickNextInterleaved, pickNextPlanned } from './reviewOrder';

const mk = (n, f) => [
  ...Array.from({ length: f }, (_, i) => ({ fullPath: `f${String(i).padStart(2, '0')}.md`, track: 'flashcard' })),
  ...Array.from({ length: n }, (_, i) => ({ fullPath: `n${String(i).padStart(2, '0')}.md`, track: 'read' })),
];
const pat = notes => orderQueue(notes).map(x => (x.track === 'flashcard' ? 'F' : 'N')).join('');

describe('orderQueue pattern', () => {
  it.each([
    [11, 3, 'NNNNFNNNNFNNNF'],
    [2, 4, 'NFNFFF'],
    [0, 3, 'FFF'],
    [4, 0, 'NNNN'],
    [0, 0, ''],
    [3, 3, 'NFNFNF'],
    [1, 1, 'NF'],
    [5, 1, 'NNNNNF'],
    [7, 2, 'NNNNFNNNF'],
  ])('%iN %iF -> %s', (n, f, want) => expect(pat(mk(n, f))).toBe(want));

  it('regression: never two flashcards adjacent while reads remain unplaced to fill gaps (old "F F NNN" bug)', () => {
    const p = pat(mk(9, 3));
    expect(p).not.toMatch(/^F/);
    expect(p).not.toContain('FF');
    expect(p.endsWith('F')).toBe(true);
  });

  it('recomputed from remaining after completions', () => {
    const notes = mk(11, 3);
    const left = orderQueue(notes).slice(4); // 4 reads done
    expect(pat(left)).toBe('NNNFNNFNNF'); // 7N 3F
  });

  it('keeps DFS order within each track', () => {
    const notes = [
      { fullPath: 'b.md', track: 'read' }, { fullPath: 'a.md', track: 'read' },
      { fullPath: 'z.md', track: 'flashcard' }, { fullPath: 'y.md', track: 'flashcard' },
    ];
    expect(orderQueue(notes).map(x => x.fullPath)).toEqual(['a.md', 'y.md', 'b.md', 'z.md']);
  });
});

describe('pickNextInterleaved', () => {
  it('first item is a read note when reads exist', () => expect(pickNextInterleaved(mk(5, 2)).track).toBe('read'));
  it('flashcard only when no reads', () => expect(pickNextInterleaved(mk(0, 2)).track).toBe('flashcard'));
  it('null when empty', () => expect(pickNextInterleaved([])).toBeNull());
});

describe('pickNextPlanned walk', () => {
  const walk = (notes) => {
    let left = [...notes], plan = null, out = '';
    while (left.length) {
      const r = pickNextPlanned(left, plan);
      plan = r.plan;
      out += r.note.track === 'flashcard' ? 'F' : 'N';
      left = left.filter(x => x !== r.note);
    }
    return out;
  };
  it('11N/3F completing one by one', () => expect(walk(mk(11, 3))).toBe('NNNNFNNNNFNNNF'));
  it('2N/4F', () => expect(walk(mk(2, 4))).toBe('NFNFFF'));
  it('rebuilds when a planned non-head item vanishes', () => {
    const notes = mk(6, 2);
    const { plan } = pickNextPlanned(notes, null);
    const left = notes.filter(n => n.fullPath !== 'n05.md' && n.fullPath !== plan[0].fullPath);
    const r = pickNextPlanned(left, plan);
    expect(r.plan.map(x => (x.track === 'flashcard' ? 'F' : 'N')).join('')).toBe('NNFNNF');
  });
});
