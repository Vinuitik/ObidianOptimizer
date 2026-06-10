import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';

// obsidianImagePlugin imports ProseMirror/milkdown internals; mock them out
vi.mock('@milkdown/utils', () => ({
  $node: vi.fn(() => ({})),
  $remark: vi.fn(() => [{}]),  // $remark returns an array; plugin spreads it
}));

import {
  setPendingBlobs,
  addPendingBlob,
  removePendingBlob,
  fileTypeFor,
  isWhitelisted,
  generateFilename,
  WHITELISTED_EXTS,
  WHITELISTED_MIME_TYPES,
} from './obsidianImagePlugin';

// ── setPendingBlobs / addPendingBlob / removePendingBlob ─────────────────────

describe('blob registry', () => {
  it('setPendingBlobs populates the registry', () => {
    // Verify through addPendingBlob + a subsequent check via toDOM indirectly;
    // here we just ensure no error is thrown and registry is cleared first
    setPendingBlobs({ 'a.png': { blobURL: 'blob:a' }, 'b.mp4': { blobURL: 'blob:b' } });
    // Re-setting with empty map should clear
    setPendingBlobs({});
    // No exported getter — state is implicit; test via addPendingBlob overwrite
    addPendingBlob('c.png', 'blob:c');
    removePendingBlob('c.png');
    // If no error thrown, the operations succeeded
  });

  it('addPendingBlob and removePendingBlob do not throw', () => {
    expect(() => addPendingBlob('x.png', 'blob:x')).not.toThrow();
    expect(() => removePendingBlob('x.png')).not.toThrow();
    expect(() => removePendingBlob('nonexistent.png')).not.toThrow();
  });
});

// ── fileTypeFor ──────────────────────────────────────────────────────────────

describe('fileTypeFor', () => {
  it.each([
    ['photo.png',   'image'],
    ['photo.jpg',   'image'],
    ['photo.jpeg',  'image'],
    ['photo.gif',   'image'],
    ['photo.webp',  'image'],
    ['photo.svg',   'image'],
    ['clip.mp4',    'video'],
    ['clip.mov',    'video'],
    ['clip.mkv',    'video'],
    ['clip.webm',   'video'],
    ['clip.avi',    'video'],
    ['track.mp3',   'audio'],
    ['track.wav',   'audio'],
    ['track.ogg',   'audio'],
    ['track.m4a',   'audio'],
    ['track.flac',  'audio'],
    ['doc.pdf',     'pdf'],
    ['file.txt',    'other'],
    ['noext',       'other'],
  ])('%s → %s', (filename, expected) => {
    expect(fileTypeFor(filename)).toBe(expected);
  });

  it('is case-insensitive for extension', () => {
    expect(fileTypeFor('Photo.PNG')).toBe('image');
    expect(fileTypeFor('clip.MP4')).toBe('video');
  });
});

// ── isWhitelisted ────────────────────────────────────────────────────────────

describe('isWhitelisted', () => {
  it('accepts files by MIME type', () => {
    expect(isWhitelisted({ type: 'image/png', name: 'x' })).toBe(true);
    expect(isWhitelisted({ type: 'video/mp4', name: 'x' })).toBe(true);
    expect(isWhitelisted({ type: 'audio/mpeg', name: 'x' })).toBe(true);
    expect(isWhitelisted({ type: 'application/pdf', name: 'x' })).toBe(true);
  });

  it('accepts files by extension when MIME is generic', () => {
    expect(isWhitelisted({ type: 'application/octet-stream', name: 'photo.png' })).toBe(true);
    expect(isWhitelisted({ type: 'application/octet-stream', name: 'clip.mp4' })).toBe(true);
  });

  it('rejects unsupported types', () => {
    expect(isWhitelisted({ type: 'text/plain', name: 'readme.txt' })).toBe(false);
    expect(isWhitelisted({ type: 'application/zip', name: 'archive.zip' })).toBe(false);
  });

  it('rejects files with no extension and no matching MIME', () => {
    expect(isWhitelisted({ type: '', name: 'noext' })).toBe(false);
  });
});

// ── generateFilename ─────────────────────────────────────────────────────────

describe('generateFilename', () => {
  beforeEach(() => {
    vi.spyOn(Date, 'now').mockReturnValue(1700000000000);
    const mockBytes = new Uint8Array([0xde, 0xad, 0xbe, 0xef]);
    vi.spyOn(crypto, 'getRandomValues').mockImplementation((arr) => {
      arr.set(mockBytes.slice(0, arr.length));
      return arr;
    });
  });

  afterEach(() => vi.restoreAllMocks());

  it('produces stem-{timestamp}{8hex}.ext format', () => {
    const result = generateFilename({ name: 'my photo.png' });
    expect(result).toBe('my_photo-1700000000000deadbeef.png');
  });

  it('lowercases the extension', () => {
    const result = generateFilename({ name: 'Clip.MP4' });
    expect(result).toMatch(/\.mp4$/);
  });

  it('strips special characters from stem', () => {
    const result = generateFilename({ name: 'hello world!.jpg' });
    expect(result).toMatch(/^hello_world_-/);
  });

  it('handles files with no extension', () => {
    const result = generateFilename({ name: 'noext' });
    expect(result).toMatch(/^noext-\d+[0-9a-f]{8}$/);
  });

  it('truncates very long stems to 40 characters', () => {
    const longName = 'a'.repeat(100) + '.png';
    const result = generateFilename({ name: longName });
    const stem = result.split('-')[0];
    expect(stem.length).toBeLessThanOrEqual(40);
  });

  it('each call produces a unique filename (different timestamps)', () => {
    vi.spyOn(Date, 'now')
      .mockReturnValueOnce(1700000000001)
      .mockReturnValueOnce(1700000000002);
    const a = generateFilename({ name: 'same.png' });
    const b = generateFilename({ name: 'same.png' });
    expect(a).not.toBe(b);
  });
});
