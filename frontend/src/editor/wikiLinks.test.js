import { describe, it, expect, vi, beforeEach } from 'vitest';
import { EditorView } from '@codemirror/view';
import { EditorState } from '@codemirror/state';
import { markdown, markdownLanguage } from '@codemirror/lang-markdown';
import { CompletionContext } from '@codemirror/autocomplete';

vi.mock('../api/notes', () => {
  class ApiError extends Error { constructor(status) { super(String(status)); this.status = status; } }
  return { searchNotes: vi.fn(), ApiError };
});
vi.mock('../utils/offlineSearch', () => ({ offlineKeywordSearch: vi.fn() }));

import { searchNotes, ApiError } from '../api/notes';
import { offlineKeywordSearch } from '../utils/offlineSearch';
import { searchWithFallback } from '../utils/useSearch';
import useStore from '../store/useStore';
import { renderMarkdown } from '../utils/markdown';
import { resolveWikiTarget } from '../utils/wikiResolve';
import { wikiLinksExtension, wikiCompletionSource } from './wikiLinks';

function mount(doc, anchor = 0) {
  const parent = document.createElement('div');
  document.body.appendChild(parent);
  return new EditorView({
    parent,
    state: EditorState.create({
      doc, selection: { anchor },
      extensions: [markdown({ base: markdownLanguage }), wikiLinksExtension()],
    }),
  });
}

describe('wiki-link decorations', () => {
  it('marks target and hides brackets off the cursor line', () => {
    const view = mount('line one\n[[Foo]] and [[Bar|the bar]]', 0);
    const links = [...view.dom.querySelectorAll('.wiki-link')];
    expect(links.map(l => l.textContent)).toEqual(['Foo', 'the bar']);
    expect(links.map(l => l.getAttribute('data-wiki-link'))).toEqual(['Foo', 'Bar']);
    expect(view.dom.querySelector('.cm-line:nth-child(2)').textContent).toBe('Foo and the bar');
  });

  it('reveals brackets and alias target on the active line', () => {
    const view = mount('[[Bar|the bar]]', 3);
    expect(view.dom.querySelector('.cm-line').textContent).toBe('[[Bar|the bar]]');
  });

  it('leaves unclosed, embed and inline-code forms alone', () => {
    const view = mount('x\n[[foo\n![[img.png]]\n`[[code]]`', 0);
    expect(view.dom.querySelectorAll('.wiki-link').length).toBe(0);
    expect(view.dom.textContent).toContain('[[foo');
  });
});

describe('completion source', () => {
  const ctxFor = (doc) => new CompletionContext(
    EditorState.create({ doc, extensions: [markdown()] }), doc.length, true);

  it('queries search and returns basenames; no popup on empty / non-trigger', async () => {
    const search = vi.fn().mockResolvedValue([{ notePath: '/v/dir/Alpha Note.md', snippet: 'dir' }]);
    const src = wikiCompletionSource(search, 0);
    const res = await src(ctxFor('see [[alp'));
    expect(search).toHaveBeenCalledWith('alp', expect.objectContaining({ signal: expect.anything() }));
    expect(res.from).toBe(6);
    expect(res.options.map(o => o.label)).toEqual(['Alpha Note']);
    expect(await src(ctxFor('see [['))).toBeNull();
    expect(await src(ctxFor('see [[a]] b'))).toBeNull();
  });

  it('apply inserts [[basename]] and consumes an existing ]]', async () => {
    const search = vi.fn().mockResolvedValue([{ notePath: 'Alpha.md', snippet: '' }]);
    const res = await wikiCompletionSource(search, 0)(ctxFor('[[al'));
    const view = mount('[[al]] tail', 4);
    res.options[0].apply(view, res.options[0], 2, 4);
    expect(view.state.doc.toString()).toBe('[[Alpha]] tail');
    expect(view.state.selection.main.head).toBe(9);
    const v2 = mount('[[al', 4);
    res.options[0].apply(v2, res.options[0], 2, 4);
    expect(v2.state.doc.toString()).toBe('[[Alpha]]');
  });
});

describe('searchWithFallback', () => {
  beforeEach(() => { vi.clearAllMocks(); });
  it('falls back offline on unreachable, not on 401, rethrows abort', async () => {
    offlineKeywordSearch.mockResolvedValue([{ notePath: 'a.md', snippet: '' }]);
    searchNotes.mockRejectedValueOnce(new TypeError('fetch failed'));
    expect(await searchWithFallback('a')).toEqual([{ notePath: 'a.md', snippet: '' }]);
    searchNotes.mockRejectedValueOnce(new ApiError(401));
    expect(await searchWithFallback('a')).toEqual([]);
    const abort = Object.assign(new Error('x'), { name: 'AbortError' });
    searchNotes.mockRejectedValueOnce(abort);
    await expect(searchWithFallback('a')).rejects.toBe(abort);
  });
});

describe('click resolution', () => {
  const openTab = vi.fn();
  beforeEach(() => {
    openTab.mockClear();
    useStore.setState({ noteIndex: new Map([['alpha', '/v/Alpha.md'], ['dir/beta', '/v/dir/Beta.md'], ['beta', '/v/dir/Beta.md']]), openTab, isMutable: false });
  });
  it('resolves full target, basename, strips #heading and |alias', () => {
    const idx = useStore.getState().noteIndex;
    expect(resolveWikiTarget(idx, 'Alpha#Intro')).toBe('/v/Alpha.md');
    expect(resolveWikiTarget(idx, 'x/Beta|al')).toBe('/v/dir/Beta.md');
    expect(resolveWikiTarget(idx, 'Missing')).toBeNull();
  });
  it('read-only click opens tab; edit mode needs Ctrl', () => {
    const view = mount('[[Alpha]]', 8);
    const el = view.dom.querySelector('.wiki-link');
    el.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }));
    expect(openTab).toHaveBeenCalledWith('/v/Alpha.md');
    openTab.mockClear();
    useStore.setState({ isMutable: true });
    el.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }));
    expect(openTab).not.toHaveBeenCalled();
    el.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true, ctrlKey: true }));
    expect(openTab).toHaveBeenCalledWith('/v/Alpha.md');
  });
});

describe('markdown rule', () => {
  it('renders spans, alias, and skips embeds', () => {
    const html = renderMarkdown('[[A b]] [[T|al]] ![[pic.png]] [[open');
    expect(html).toContain('<span class="wiki-link" data-wiki-link="A b">A b</span>');
    expect(html).toContain('<span class="wiki-link" data-wiki-link="T">al</span>');
    expect(html).toContain('/api/images/pic.png');
    expect(html).toContain('[[open');
  });
});
