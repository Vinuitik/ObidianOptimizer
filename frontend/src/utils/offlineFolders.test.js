import { describe, it, expect } from 'vitest';
import { offlineChildrenOf } from './offlineFolders';

const ROOT = '/vault';
const PATHS = [
  '/vault/note.md',
  '/vault/Physics/intro.md',
  '/vault/Physics/Mechanics/forces.md',
  '/vault/Physics/Mechanics/energy.md',
  '/vault/Chemistry/atoms.md',
  '/vault/_inbox/staged.md',
];

describe('offlineChildrenOf', () => {
  it('lists direct child folders at the root, excluding _inbox', () => {
    const { current, parent, dirs } = offlineChildrenOf(PATHS, null, ROOT);
    expect(current).toBe(ROOT);
    expect(parent).toBeNull();
    expect(dirs.map(d => d.name)).toEqual(['Chemistry', 'Physics']);
  });

  it('descends into a subfolder and reports its parent', () => {
    const { current, parent, dirs } = offlineChildrenOf(PATHS, '/vault/Physics', ROOT);
    expect(current).toBe('/vault/Physics');
    expect(parent).toBe(ROOT);
    expect(dirs).toEqual([{ path: '/vault/Physics/Mechanics', name: 'Mechanics' }]);
  });

  it('a folder with only notes directly inside has no children', () => {
    const { dirs } = offlineChildrenOf(PATHS, '/vault/Physics/Mechanics', ROOT);
    expect(dirs).toEqual([]);
  });

  it('a folder with no cached notes anywhere in its subtree is invisible offline', () => {
    const { dirs } = offlineChildrenOf(PATHS, ROOT, ROOT);
    expect(dirs.some(d => d.name === '_inbox')).toBe(false);
  });

  it('with nothing cached yet, returns an empty root instead of throwing', () => {
    expect(offlineChildrenOf([], null, null)).toEqual({ current: '', parent: null, dirs: [] });
  });
});
