import { describe, it, expect } from 'vitest';
import { mediaUrlsForNote, vaultMediaUrl } from './noteMedia';

describe('vaultMediaUrl', () => {
  it('maps vault resources → /vault-media/, _workspace → /workspace/, encodes segments', () => {
    expect(vaultMediaUrl('resources/videos/lec 1.mp4')).toBe('/vault-media/videos/lec%201.mp4');
    expect(vaultMediaUrl('_workspace/audio/x.mp3')).toBe('/workspace/audio/x.mp3');
  });
  it('returns null for external refs (streamed live, never warmed)', () => {
    expect(vaultMediaUrl('https://youtube.com/watch?v=x')).toBeNull();
    expect(vaultMediaUrl('')).toBeNull();
  });
});

describe('mediaUrlsForNote', () => {
  it('collects image embeds', () => {
    const urls = mediaUrlsForNote('text ![[diagram.png]] more ![[b.jpg]]');
    expect(urls).toContain('/api/images/diagram.png');
    expect(urls).toContain('/api/images/b.jpg');
  });

  it('adds the local A/V source file from the ## Source footer', () => {
    const note = 'body\n\n## Source\nlocal: resources/videos/talk.mp4\nclip: 39-131\n';
    expect(mediaUrlsForNote(note)).toContain('/vault-media/videos/talk.mp4');
  });

  it('excludes local PDFs (rendered via /pdf-page server-side, not warmed as a blob)', () => {
    const note = 'body\n\n## Source\nlocal: resources/books/x.pdf\npages: 4,5\n';
    expect(mediaUrlsForNote(note)).toEqual([]);
  });

  it('ignores external video refs (no local file to warm)', () => {
    const note = 'body\n\n## Source\nhttps://youtu.be/abc\nclip: 10-20\n';
    expect(mediaUrlsForNote(note)).toEqual([]);
  });

  it('dedups repeated embeds', () => {
    expect(mediaUrlsForNote('![[a.png]] ![[a.png]]')).toEqual(['/api/images/a.png']);
  });
});
