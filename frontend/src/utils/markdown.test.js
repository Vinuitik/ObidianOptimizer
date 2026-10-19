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
