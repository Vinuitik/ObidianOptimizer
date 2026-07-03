import { describe, it, expect } from 'vitest';
import { toReadableText, tokenize, orpIndex, dwellMs, splitAtOrp } from './rsvp';

describe('toReadableText', () => {
  it('drops frontmatter, scaffolding, embeds, and markers', () => {
    const md = `---
sr-due: 2026-07-03
---
# Title

**Bold** prose with [[Link|alias]] and ![[img.png]].

## Sequence
Previous: [[X]]`;
    const t = toReadableText(md);
    expect(t).toContain('Bold prose with alias and');
    expect(t).not.toContain('img.png');
    expect(t).not.toContain('sr-due');
    expect(t).not.toContain('Previous');   // scaffolding cut
  });
});

describe('tokenize', () => {
  it('splits readable prose into words', () => {
    expect(tokenize('# H\n\none two three')).toEqual(['one', 'two', 'three']);
  });
});

describe('orpIndex', () => {
  it('pivots left-of-centre by length band', () => {
    expect(orpIndex('a')).toBe(0);
    expect(orpIndex('cells')).toBe(1);
    expect(orpIndex('mitochondria')).toBe(3);
  });
});

describe('dwellMs', () => {
  it('scales with WPM and lingers on sentence ends', () => {
    const plain = dwellMs('word', 300);
    const stop = dwellMs('end.', 300);
    expect(stop).toBeGreaterThan(plain);
    // faster WPM → shorter dwell
    expect(dwellMs('word', 600)).toBeLessThan(dwellMs('word', 300));
  });
});

describe('splitAtOrp', () => {
  it('splits into before/pivot/after around the ORP letter', () => {
    expect(splitAtOrp('cells')).toEqual(['c', 'e', 'lls']);
  });
});
