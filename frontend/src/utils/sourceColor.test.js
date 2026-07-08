import { describe, it, expect } from 'vitest';
import { groupBySource, captureLabel } from './sourceColor.js';

// ── captureLabel ──────────────────────────────────────────────────────────────

describe('captureLabel', () => {
  it('shows a 1-based "#N" for an original note (minor 0 / absent)', () => {
    expect(captureLabel({ captureSeq: 0 })).toBe('#1');
    expect(captureLabel({ captureSeq: 10, captureSeqMinor: 0 })).toBe('#11');
  });

  it('shows "#N-M" for a manually-split sibling', () => {
    expect(captureLabel({ captureSeq: 10, captureSeqMinor: 1 })).toBe('#11-1');
    expect(captureLabel({ captureSeq: 10, captureSeqMinor: 2 })).toBe('#11-2');
  });

  it('returns null when there is no capture order', () => {
    expect(captureLabel({})).toBeNull();
    expect(captureLabel({ captureSeq: null })).toBeNull();
  });
});

// ── groupBySource: splits slot in right after their original ────────────────────

describe('groupBySource sub-order', () => {
  it('orders a split sibling (#11-1) directly after its original (#11), before #12', () => {
    // Deliberately shuffled input; same capture, seq 10 has an original + one split.
    const items = [
      { path: 'c.md', captureId: 'cap', captureSeq: 11 },                       // #12
      { path: 'b.md', captureId: 'cap', captureSeq: 10, captureSeqMinor: 1 },   // #11-1
      { path: 'a.md', captureId: 'cap', captureSeq: 10 },                       // #11
    ];
    const ordered = groupBySource(items).map(i => i.path);
    expect(ordered).toEqual(['a.md', 'b.md', 'c.md']);
  });

  it('orders multiple splits by minor (#11, #11-1, #11-2)', () => {
    const items = [
      { path: 'x2.md', captureId: 'cap', captureSeq: 10, captureSeqMinor: 2 },
      { path: 'x0.md', captureId: 'cap', captureSeq: 10 },
      { path: 'x1.md', captureId: 'cap', captureSeq: 10, captureSeqMinor: 1 },
    ];
    expect(groupBySource(items).map(i => i.path)).toEqual(['x0.md', 'x1.md', 'x2.md']);
  });
});
