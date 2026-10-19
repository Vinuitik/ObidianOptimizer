import { describe, it, expect } from 'vitest';
import { renderMarkdown } from './markdown';

describe('renderMarkdown W4', () => {
  it('renders #tags, not in code/urls/headings', () => {
    const html = renderMarkdown('# Head\n\ntext #todo and #a/b-c `#inl` http://x.com/#frag #123\n\n```\n#blk\n```');
    expect(html).toContain('<span class="md-tag">#todo</span>');
    expect(html).toContain('<span class="md-tag">#a/b-c</span>');
    expect(html).not.toContain('md-tag">#inl');
    expect(html).not.toContain('md-tag">#blk');
    expect(html).not.toContain('md-tag">#frag');
    expect(html).not.toContain('md-tag">#123');
    expect(html).not.toContain('md-tag">#Head');
  });
  it('highlights fenced code with prism', () => {
    const html = renderMarkdown('```python\ndef f(): return 1\n```');
    expect(html).toContain('language-python');
    expect(html).toContain('token keyword');
  });
  it('unknown language falls back to escaped code', () => {
    expect(renderMarkdown('```zzz\n<b>\n```')).toContain('&lt;b&gt;');
  });
});

describe('wikilink / embed / tag inline pipeline', () => {
  it('no-alias wikilink shows basename only; alias wins', () => {
    const h = renderMarkdown('[[a/b/Note]] [[a/b/Note|Al]]');
    expect(h).toContain('data-wiki-link="a/b/Note">Note</span>');
    expect(h).toContain('data-wiki-link="a/b/Note">Al</span>');
  });
  it('[[#heading]] is not a tag', () => {
    const h = renderMarkdown('[[Page#heading]] and [[#top]] #real');
    expect(h).not.toContain('md-tag">#heading');
    expect(h).not.toContain('md-tag">#top');
    expect(h).toContain('md-tag">#real');
  });
  it('wikilinks, embeds and tags are inert in code and math', () => {
    const h = renderMarkdown('`[[x]] ![[a.png]] #t`\n\n```\n[[y]] #u\n```\n\n$a[[b]]c #d$');
    expect(h).not.toContain('wiki-link');
    expect(h).not.toContain('embedded-image');
    expect(h).not.toContain('md-tag');
  });
});

describe('offline media parity', () => {
  it('every embed src the renderer emits is a key the offline warm caches', async () => {
    const { mediaUrlsForNote } = await import('./noteMedia');
    const note = '![[a b.png]] ![[v.mp4]] ![[d.pdf]]';
    const keys = new Set(mediaUrlsForNote(note, null));
    const srcs = [...renderMarkdown(note).matchAll(/(?:src|href)="(\/vault-media[^"]+)"/g)].map(m => m[1]);
    expect(srcs.length).toBe(3);
    for (const s of srcs) expect(keys.has(s)).toBe(true);
  });
});
