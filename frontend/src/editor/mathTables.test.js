import { describe, it, expect } from 'vitest';
import { EditorState } from '@codemirror/state';
import { EditorView } from '@codemirror/view';
import { markdown, markdownLanguage } from '@codemirror/lang-markdown';
import { ensureSyntaxTree } from '@codemirror/language';
import { mathExtension, findMath, renderMath } from './math';
import { tablesExtension, parseTable } from './tables';
import { renderMarkdown } from '../utils/markdown';

function mk(doc, anchor = 0) {
  const state = EditorState.create({
    doc, selection: { anchor },
    extensions: [markdown({ base: markdownLanguage }), mathExtension(), tablesExtension()],
  });
  ensureSyntaxTree(state, doc.length, 1000);
  return state;
}
function mount(doc, anchor = 0) {
  const parent = document.createElement('div');
  document.body.append(parent);
  const view = new EditorView({ parent, state: mk(doc, anchor) });
  ensureSyntaxTree(view.state, doc.length, 1000);
  view.dispatch({ selection: { anchor } }); // refresh decorations against full tree
  return view;
}

describe('findMath', () => {
  it('detects the three syntaxes', () => {
    const m = findMath(mk('a $x^2$ b\n\n$$\ny=1\n$$\n\n\\[z\\]\n'));
    expect(m.map(x => [x.src, x.display])).toEqual([['x^2', false], ['y=1', true], ['z', true]]);
  });
  it('ignores prices and spaced dollars', () => {
    expect(findMath(mk('costs $5 and $10 total'))).toEqual([]);
    expect(findMath(mk('a $ x $ b'))).toEqual([]);
  });
  it('ignores code', () => {
    expect(findMath(mk('`$x$` and\n\n```\n$$\ny\n$$\n```\n'))).toEqual([]);
  });
  it('block math sharing a paragraph is not block', () => {
    expect(findMath(mk('text $$y$$ more')).length).toBe(0);
  });
});

describe('math widget', () => {
  it('widget when outside, raw when inside, source unchanged', () => {
    const doc = 'hello\n\nsome $x^2$ here';
    const out = mount(doc, 0);
    expect(out.dom.querySelector('.cm-math-inline .katex')).toBeTruthy();
    expect(out.state.doc.toString()).toBe(doc);
    const inside = mount(doc, doc.indexOf('x^2') + 1);
    expect(inside.dom.querySelector('.cm-math-inline')).toBeNull();
    expect(inside.dom.textContent).toContain('$x^2$');
  });
  it('error path never throws and shows source', () => {
    expect(renderMath('\\frac{', false).error).toBeTruthy();
    const v = mount('hi\n\n$\\frac{$', 0);
    const el = v.dom.querySelector('.cm-math-error');
    expect(el?.textContent).toBe('$\\frac{$');
  });
});

describe('tables', () => {
  const doc = 'intro\n\n| A | B |\n|---|:-:|\n| **x** | `y` |\n\nafter';
  it('parses', () => {
    const t = parseTable('| A | B |\n|---|:-:|\n| 1 | 2 |');
    expect(t.header).toEqual(['A', 'B']);
    expect(t.align).toEqual(['', 'center']);
    expect(t.rows).toEqual([['1', '2']]);
  });
  it('widget outside, raw inside', () => {
    const out = mount(doc, 0);
    expect(out.dom.querySelector('.cm-table-wrap table th')?.textContent).toBe('A');
    expect(out.dom.querySelector('td strong')?.textContent).toBe('x');
    expect(out.dom.querySelector('td code')?.textContent).toBe('y');
    const inside = mount(doc, doc.indexOf('| A') + 3);
    expect(inside.dom.querySelector('.cm-table-wrap')).toBeNull();
    expect(inside.state.doc.toString()).toBe(doc);
  });
});

describe('markdown-it math', () => {
  it('renders three syntaxes', () => {
    const h = renderMarkdown('a $x^2$ b\n\n$$\ny=1\n$$\n\n\\[z\\]\n\n$$q$$');
    expect(h.match(/class="katex"/g).length).toBe(4);
    expect(h).toContain('katex-display');
  });
  it('leaves prices and code alone', () => {
    const h = renderMarkdown('cost $5 and $10 `$x$`\n\n```\n$$\ny\n$$\n```');
    expect(h).not.toContain('katex');
    expect(h).toContain('$x$');
  });
  it('error falls back to escaped source', () => {
    expect(renderMarkdown('$\\frac{$')).toContain('math-error');
  });
  it('tables render', () => {
    expect(renderMarkdown('| a | b |\n|---|---|\n| 1 | 2 |')).toContain('<table>');
  });
});
