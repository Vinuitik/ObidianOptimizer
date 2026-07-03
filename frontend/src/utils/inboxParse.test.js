import { describe, it, expect } from 'vitest';
import { parseLinks, parseSourceRegion, sectionBody } from './inboxParse';

const NOTE = `---
sr-due: 2026-07-03
---
# Cells

Body prose about cells.

## Sequence

Previous: [[Intro]] · Next: [[Energy]]

## Related

[[Mitochondria]] · [[Osmosis]]

## Source
https://youtu.be/abc?t=90s
pages: 2, 3
`;

describe('sectionBody', () => {
  it('returns a section body and stops at the next ##', () => {
    expect(sectionBody(NOTE, 'Related')).toBe('[[Mitochondria]] · [[Osmosis]]');
    expect(sectionBody(NOTE, 'Missing')).toBe('');
  });
});

describe('parseLinks', () => {
  it('extracts ordered sequence + related stems', () => {
    const { sequence, related } = parseLinks(NOTE);
    expect(sequence).toEqual({ prev: 'Intro', next: 'Energy' });
    expect(related).toEqual(['Mitochondria', 'Osmosis']);
  });

  it('handles a first note (no previous)', () => {
    const first = '## Sequence\n\nPrevious: — (start of source) · Next: [[Two]]';
    expect(parseLinks(first).sequence).toEqual({ prev: null, next: 'Two' });
  });

  it('is empty when there is no scaffolding', () => {
    expect(parseLinks('# Plain\n\njust prose')).toEqual({
      sequence: { prev: null, next: null }, related: [],
    });
  });
});

describe('parseSourceRegion', () => {
  it('pulls pages and a start time out of the Source footer', () => {
    const r = parseSourceRegion(NOTE, 'https://youtu.be/abc?t=90s');
    expect(r.pages).toEqual([2, 3]);
    expect(r.startSeconds).toBe(90);
    expect(r.ref).toBe('https://youtu.be/abc?t=90s');
  });

  it('parses a mm:ss "from" timestamp', () => {
    const r = parseSourceRegion('## Source\nlocal.mp4\nfrom 2:05', 'local.mp4');
    expect(r.startSeconds).toBe(125);
  });
});
