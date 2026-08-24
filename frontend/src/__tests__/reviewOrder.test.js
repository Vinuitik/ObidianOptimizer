import { describe, it, expect } from 'vitest';
import { comparePaths, pickNextInOrder } from '../pages/reviewOrder';

const n = (fullPath) => ({ fullPath });

describe('comparePaths — DFS pre-order comparator', () => {
  it('sorts siblings in the same folder alphabetically', () => {
    expect(comparePaths('Folder/b.md', 'Folder/a.md')).toBeGreaterThan(0);
    expect(comparePaths('Folder/a.md', 'Folder/b.md')).toBeLessThan(0);
  });

  it('puts files before subdirectories at the same level, regardless of name', () => {
    // "zzz.md" (file) sorts before "aaa/..." (subdir) even though 'a' < 'z'.
    expect(comparePaths('Folder/zzz.md', 'Folder/aaa/note.md')).toBeLessThan(0);
    expect(comparePaths('Folder/aaa/note.md', 'Folder/zzz.md')).toBeGreaterThan(0);
  });

  it('exhausts a subfolder (whole subtree) before moving to the next sibling', () => {
    // Folder/Sub/* must all precede Folder/z.md if Sub sorts before z... but files
    // beat dirs, so root-level files still win. Use two subdirs to check subtree order.
    const paths = ['Folder/B/x.md', 'Folder/A/y.md', 'Folder/A/z.md'];
    const sorted = [...paths].sort(comparePaths);
    expect(sorted).toEqual(['Folder/A/y.md', 'Folder/A/z.md', 'Folder/B/x.md']);
  });

  it('treats identical paths as equal', () => {
    expect(comparePaths('Folder/a.md', 'Folder/a.md')).toBe(0);
  });
});

describe('pickNextInOrder — walk the review queue in DFS pre-order', () => {
  it('picks the next sibling in the same folder', () => {
    const list = [n('Folder/a.md'), n('Folder/b.md'), n('Folder/c.md')];
    expect(pickNextInOrder(list, 'Folder/a.md').fullPath).toBe('Folder/b.md');
  });

  it('descends into a child folder after exhausting root-level files', () => {
    const list = [n('z.md'), n('Sub/a.md'), n('Sub/b.md')];
    // "z.md" is the last root file (alphabetically last, and files precede dirs) —
    // next after it descends into Sub.
    expect(pickNextInOrder(list, 'z.md').fullPath).toBe('Sub/a.md');
  });

  it('only ascends to the next branch once a subtree is fully exhausted', () => {
    const list = [n('Sub/a.md'), n('Sub/b.md'), n('Zeta.md')];
    expect(pickNextInOrder(list, 'Sub/a.md').fullPath).toBe('Sub/b.md');
    expect(pickNextInOrder(list, 'Sub/b.md').fullPath).toBe('Zeta.md');
  });

  it('wraps to the first note in canonical order when nothing comes after', () => {
    const list = [n('Folder/a.md'), n('Folder/b.md')];
    expect(pickNextInOrder(list, 'Folder/b.md').fullPath).toBe('Folder/a.md');
  });

  it('skips a note no longer in the list (already excluded by the caller)', () => {
    // excludePath itself need not be present — its position is derived purely from
    // the path string, per comparePaths.
    const list = [n('Folder/c.md')];
    expect(pickNextInOrder(list, 'Folder/b.md').fullPath).toBe('Folder/c.md');
  });

  it('returns null when the queue is empty', () => {
    expect(pickNextInOrder([], 'Folder/a.md')).toBeNull();
  });

  it('returns the canonical-first note when there is no reference point', () => {
    const list = [n('Folder/b.md'), n('Folder/a.md')];
    expect(pickNextInOrder(list, null).fullPath).toBe('Folder/a.md');
  });
});
