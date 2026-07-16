import { describe, it, expect } from 'vitest';
import { mediaUrlsForNote, mediaEntriesForNote, vaultMediaUrl } from './noteMedia';

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
    // Must be the SAME URL the Milkdown renderer requests (obsidianImagePlugin.mediaUrl),
    // else the offline warm caches a key the review view never asks for → blank images.
    expect(urls).toContain('/vault-media/images/diagram.png');
    expect(urls).toContain('/vault-media/images/b.jpg');
  });

  it('adds the local A/V source file from the ## Source footer', () => {
    const note = 'body\n\n## Source\nlocal: resources/videos/talk.mp4\nclip: 39-131\n';
    expect(mediaUrlsForNote(note)).toContain('/vault-media/videos/talk.mp4');
  });

  it('warms the referenced PDF pages as /pdf-page PNGs (offline review needs them)', () => {
    const note = 'body\n\n## Source\nlocal: resources/books/x.pdf\npages: 4,5\n';
    const urls = mediaUrlsForNote(note);
    expect(urls).toContain('/pdf-page?path=resources%2Fbooks%2Fx.pdf&page=4');
    expect(urls).toContain('/pdf-page?path=resources%2Fbooks%2Fx.pdf&page=5');
  });

  it('warms the highlighted bbox variant too, so the default Show-region view is offline-ready', () => {
    const note = 'body\n\n## Source\nlocal: resources/books/x.pdf\npages: 4\nbbox: 4 10 20 30 40\n';
    const urls = mediaUrlsForNote(note);
    expect(urls).toContain('/pdf-page?path=resources%2Fbooks%2Fx.pdf&page=4');
    expect(urls).toContain('/pdf-page?path=resources%2Fbooks%2Fx.pdf&page=4&box=10,20,30,40');
  });

  it('ignores external video refs (no local file to warm)', () => {
    const note = 'body\n\n## Source\nhttps://youtu.be/abc\nclip: 10-20\n';
    expect(mediaUrlsForNote(note)).toEqual([]);
  });

  it('dedups repeated embeds', () => {
    expect(mediaUrlsForNote('![[a.png]] ![[a.png]]')).toEqual(['/vault-media/images/a.png']);
  });
});

describe('mediaEntriesForNote (key = player URL, fetch = rendition)', () => {
  it('A/V: cache key is the canonical player URL, fetch is the server rendition', () => {
    const note = 'b\n\n## Source\nlocal: resources/videos/talk.mp4\nclip: 39-131\n';
    const [e] = mediaEntriesForNote(note);
    expect(e.key).toBe('/vault-media/videos/talk.mp4');
    expect(e.fetch).toBe('/media-rendition?path=resources%2Fvideos%2Ftalk.mp4');
  });
  it('images have no rendition → fetch equals key', () => {
    const [e] = mediaEntriesForNote('![[a.png]]');
    expect(e).toEqual({ key: '/vault-media/images/a.png', fetch: '/vault-media/images/a.png' });
  });
});
