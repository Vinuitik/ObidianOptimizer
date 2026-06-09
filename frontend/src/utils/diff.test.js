import { describe, it, expect } from 'vitest';
import { computeHunks } from './diff.js';

describe('computeHunks', () => {
  it('returns empty array for identical text', () => {
    expect(computeHunks('a\nb\nc', 'a\nb\nc')).toEqual([]);
  });

  it('treats CRLF and LF as equal (no spurious diff)', () => {
    expect(computeHunks('a\r\nb\r\nc', 'a\nb\nc')).toEqual([]);
  });

  it('detects a single-line insertion', () => {
    const hunks = computeHunks('line1\nline3', 'line1\nline2\nline3');
    expect(hunks).toHaveLength(1);
    expect(hunks[0]).toMatchObject({ startLine: 1, deleteCount: 0, insertLines: ['line2'] });
  });

  it('detects a single-line deletion', () => {
    const hunks = computeHunks('line1\nline2\nline3', 'line1\nline3');
    expect(hunks).toHaveLength(1);
    expect(hunks[0]).toMatchObject({ startLine: 1, deleteCount: 1, insertLines: [] });
  });

  it('detects a single-line replacement', () => {
    const hunks = computeHunks('a\nb\nc', 'a\nB\nc');
    expect(hunks).toHaveLength(1);
    expect(hunks[0]).toMatchObject({ startLine: 1, deleteCount: 1, insertLines: ['B'] });
  });

  it('produces hunks that when applied back-to-front reconstruct newText', () => {
    const oldText = 'alpha\nbeta\ngamma\ndelta';
    const newText = 'alpha\nBETA\ngamma\nEPSILON\ndelta';
    const hunks = computeHunks(oldText, newText);

    // Apply hunks back-to-front to simulate backend behaviour
    const lines = oldText.split('\n');
    const sorted = [...hunks].sort((a, b) => b.startLine - a.startLine);
    for (const h of sorted) {
      lines.splice(h.startLine, h.deleteCount, ...h.insertLines);
    }
    expect(lines.join('\n')).toBe(newText);
  });

  it('handles empty oldText (replaces the implicit empty line)', () => {
    // ''.split('\n') = [''] — one empty line, which gets replaced
    const hunks = computeHunks('', 'line1\nline2');
    expect(hunks).toHaveLength(1);
    expect(hunks[0].insertLines).toEqual(['line1', 'line2']);
    expect(hunks[0].deleteCount).toBe(1); // the implicit [''] line is deleted
  });

  it('handles empty newText (collapses to implicit empty line)', () => {
    // ''.split('\n') = [''] — new content is one empty line
    const hunks = computeHunks('line1\nline2', '');
    expect(hunks).toHaveLength(1);
    expect(hunks[0].deleteCount).toBe(2);
    expect(hunks[0].insertLines).toEqual(['']); // inserts the implicit empty line
  });

  it('zero hunks when Milkdown serializes identical content (round-trip guard)', () => {
    // This is the critical test: if Milkdown serializes back to the exact same
    // string we fed it, computeHunks must return [].
    const body = '# My Note\n\nSome paragraph.\n\n- item one\n- item two\n';
    expect(computeHunks(body, body)).toEqual([]);
  });
});
