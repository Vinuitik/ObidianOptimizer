import { describe, it, expect } from 'vitest';
import { splitFrontmatter, joinFrontmatter, parseFrontmatterFields } from './frontmatter.js';

// ── splitFrontmatter ──────────────────────────────────────────────────────────

describe('splitFrontmatter', () => {
  it('separates frontmatter block and body', () => {
    const raw = '---\nsr-due: 2024-01-01\nsr-ease: 200\n---\n\n# Heading\n\nBody text.';
    const { frontmatter, body } = splitFrontmatter(raw);
    // frontmatter includes the blank-line separator
    expect(frontmatter).toBe('---\nsr-due: 2024-01-01\nsr-ease: 200\n---\n\n');
    // body starts clean, no leading blank lines
    expect(body).toBe('# Heading\n\nBody text.');
  });

  it('returns empty frontmatter when no --- opener', () => {
    const raw = '# Just a note\n\nNo frontmatter.';
    const { frontmatter, body } = splitFrontmatter(raw);
    expect(frontmatter).toBe('');
    expect(body).toBe(raw);
  });

  it('returns empty frontmatter when opener but no closer', () => {
    const raw = '---\nunclosed: true\n';
    const { frontmatter, body } = splitFrontmatter(raw);
    expect(frontmatter).toBe('');
    expect(body).toBe(raw);
  });

  it('handles CRLF line endings', () => {
    const raw = '---\r\nkey: value\r\n---\r\n\r\nBody.';
    const { frontmatter, body } = splitFrontmatter(raw);
    expect(frontmatter).toBe('---\r\nkey: value\r\n---\r\n\r\n');
    expect(body).toBe('Body.');
  });

  it('handles note with only frontmatter (empty body)', () => {
    const raw = '---\nsr-due: 2024-01-01\n---\n';
    const { frontmatter, body } = splitFrontmatter(raw);
    expect(frontmatter).toBe('---\nsr-due: 2024-01-01\n---\n');
    expect(body).toBe('');
  });

  it('does not treat --- mid-document as frontmatter opener', () => {
    const raw = '# Title\n\n---\nsome: value\n---\n';
    const { frontmatter, body } = splitFrontmatter(raw);
    expect(frontmatter).toBe('');
    expect(body).toBe(raw);
  });

  it('absorbs multiple blank lines into frontmatter', () => {
    const raw = '---\nkey: v\n---\n\n\n# Title';
    const { frontmatter, body } = splitFrontmatter(raw);
    expect(body).toBe('# Title');
    expect(frontmatter).toBe('---\nkey: v\n---\n\n\n');
  });

  it('handles no blank line between frontmatter and body', () => {
    const raw = '---\nkey: v\n---\n# Title';
    const { frontmatter, body } = splitFrontmatter(raw);
    expect(frontmatter).toBe('---\nkey: v\n---\n');
    expect(body).toBe('# Title');
  });
});

// ── joinFrontmatter ───────────────────────────────────────────────────────────

describe('joinFrontmatter', () => {
  it('round-trips: split → join restores original', () => {
    const raw = '---\nsr-due: 2024-01-01\nsr-ease: 200\n---\n\n# Heading\n\nBody.';
    const { frontmatter, body } = splitFrontmatter(raw);
    expect(joinFrontmatter(frontmatter, body)).toBe(raw);
  });

  it('round-trips a note with no frontmatter', () => {
    const raw = '# Just a heading\n\nBody text.';
    const { frontmatter, body } = splitFrontmatter(raw);
    expect(joinFrontmatter(frontmatter, body)).toBe(raw);
  });

  it('round-trips CRLF note', () => {
    const raw = '---\r\nkey: value\r\n---\r\n\r\nBody.';
    const { frontmatter, body } = splitFrontmatter(raw);
    expect(joinFrontmatter(frontmatter, body)).toBe(raw);
  });

  it('round-trips note with no separator blank line', () => {
    const raw = '---\nkey: v\n---\n# Title';
    const { frontmatter, body } = splitFrontmatter(raw);
    expect(joinFrontmatter(frontmatter, body)).toBe(raw);
  });

  it('returns body unchanged when frontmatter is empty', () => {
    expect(joinFrontmatter('', 'Some body.')).toBe('Some body.');
  });
});

// ── parseFrontmatterFields ────────────────────────────────────────────────────

describe('parseFrontmatterFields', () => {
  it('parses key:value pairs', () => {
    const fm = '---\nsr-due: 2024-01-01\nsr-interval: 3\nsr-ease: 200\n---\n\n';
    const fields = parseFrontmatterFields(fm);
    expect(fields).toEqual([
      { key: 'sr-due', value: '2024-01-01' },
      { key: 'sr-interval', value: '3' },
      { key: 'sr-ease', value: '200' },
    ]);
  });

  it('returns em-dash for empty value', () => {
    const fm = '---\nsr-due:\n---\n';
    const fields = parseFrontmatterFields(fm);
    expect(fields[0].value).toBe('—');
  });

  it('returns [] for empty frontmatter', () => {
    expect(parseFrontmatterFields('')).toEqual([]);
  });

  it('ignores lines without a colon', () => {
    const fm = '---\nno-colon-line\nkey: val\n---\n';
    const fields = parseFrontmatterFields(fm);
    expect(fields).toHaveLength(1);
    expect(fields[0]).toEqual({ key: 'key', value: 'val' });
  });
});
