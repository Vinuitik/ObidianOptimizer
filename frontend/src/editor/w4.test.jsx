import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, act } from '@testing-library/react';
import { EditorState } from '@codemirror/state';
import { EditorView } from '@codemirror/view';
import { ensureSyntaxTree } from '@codemirror/language';
import useStore from '../store/useStore';
import { computeHunks } from '../utils/diff';
import { markdown, markdownLanguage } from '@codemirror/lang-markdown';
import { languages } from '@codemirror/language-data';
import { livePreviewExtension } from './livePreview';
import { hashtagsExtension } from './hashtags';
import { codeBlocksExtension } from './codeBlocks';
import { mathExtension, findMath } from './math';
import { tablesExtension } from './tables';
import { wikiLinksExtension } from './wikiLinks';
import { embedsExtension } from './embeds';
import CmEditor, { baseExtensions, detectLineSeparator } from './CmEditor';

const mount = (doc, extra = []) => {
  const parent = document.createElement('div');
  document.body.appendChild(parent);
  const view = new EditorView({
    parent,
    state: EditorState.create({ doc, extensions: [...extra, baseExtensions({ onDocChange() {}, onFlush() {}, isMutable: true })] }),
  });
  ensureSyntaxTree(view.state, view.state.doc.length, 5000);
  view.dispatch({}); // rebuild decorations against the parsed tree
  return view;
};
const tagsOf = (view) => [...view.dom.querySelectorAll('.md-tag')].map(e => e.textContent);

describe('CRLF round trip', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it('edit one line of a CRLF note changes only that line', () => {
    const body = '# T\r\nline one\r\nline two\r\n\r\nend\r\n';
    useStore.setState({
      currentNotePath: '/v/C.md', isMutable: true, editorResetKey: 0,
      pendingFrontmatter: '---\r\na: 1\r\n---\r\n', pendingRaw: '---\r\na: 1\r\n---\r\n' + body,
    });
    const original = useStore.getState().pendingRaw;
    const { container } = render(<CmEditor />);
    const view = EditorView.findFromDOM(container.querySelector('.cm-editor'));
    expect(view.state.sliceDoc()).toBe(body);
    const at = view.state.doc.toString().indexOf('two');
    act(() => { view.dispatch({ changes: { from: at, insert: 'X' } }); });
    act(() => { vi.advanceTimersByTime(300); });
    const out = useStore.getState().pendingRaw;
    expect(out).toBe(original.replace('line two', 'line Xtwo'));
    expect(out.replace(/\r\n/g, '').includes('\n')).toBe(false);
    expect(JSON.stringify(computeHunks(original, out)).length).toBeLessThan(400);
  });

  it('detectLineSeparator: CRLF only when every newline is CRLF', () => {
    expect(detectLineSeparator('a\r\nb\r\n')).toBe('\r\n');
    expect(detectLineSeparator('a\nb')).toBeNull();
    expect(detectLineSeparator('a\r\nb\nc')).toBeNull();
    expect(detectLineSeparator('abc')).toBeNull();
  });

  it('Enter inserts CRLF in a CRLF note', () => {
    const view = mount('a\r\nb', [EditorState.lineSeparator.of('\r\n')]);
    view.dispatch(view.state.replaceSelection(view.state.lineBreak));
    expect(view.state.sliceDoc()).toBe('\r\na\r\nb');
  });
});

describe('hashtags', () => {
  it('decorates tags but not code, wikilinks, urls', () => {
    const view = mount([
      '# Heading', '', 'text #todo and #a/b-c_1 and #123', '`#inline` x',
      'see [[note#sec]] and [[#anch]] http://x.com/#frag', '```', '#incode', '```',
    ].join('\n'));
    const tags = tagsOf(view);
    expect(tags).toContain('#todo');
    expect(tags).toContain('#a/b-c_1');
    for (const bad of ['#123', '#incode', '#inline', '#sec', '#anch', '#frag']) expect(tags).not.toContain(bad);
    view.destroy();
  });
});

describe('codeBlocks highlighting', () => {
  it('lazy-loads the fence language and emits token spans inside the block', async () => {
    const view = mount('```python\ndef f(x):\n    return "s"\n```\n', [codeBlocksExtension()]);
    view.dispatch({ selection: { anchor: view.state.doc.length } });
    const spans = () => view.dom.querySelectorAll('.cm-md-codeblock span[class]').length;
    for (let i = 0; i < 80 && !spans(); i++) {
      await new Promise(r => setTimeout(r, 50));
      ensureSyntaxTree(view.state, view.state.doc.length, 5000);
      view.dispatch({});
    }
    expect(spans()).toBeGreaterThan(0);
    view.destroy();
  });
});

describe('codeBlocks', () => {
  it('styles fenced block lines and dims fences off-cursor', () => {
    const view = mount('para\n\n```js\nconst a = 1;\n```\n');
    view.dispatch({ selection: { anchor: 0 } });
    expect(view.dom.querySelectorAll('.cm-md-codeblock').length).toBe(3);
    expect(view.dom.querySelectorAll('.cm-md-fence').length).toBe(2);
    view.dispatch({ selection: { anchor: view.state.doc.line(3).from + 1 } });
    expect(view.dom.querySelectorAll('.cm-md-fence').length).toBe(1);
    view.destroy();
  });
});

function bigNote(lines = 5000) {
  const out = [];
  let i = 0;
  while (out.length < lines) {
    i++;
    out.push(`## Section ${i} #topic${i % 7}`, '', `Paragraph with **bold**, *em*, \`code\`, [link](http://e.com/${i}) and [[Note ${i}]] #tag${i % 5}.`, '',
      '- item one', '- item two with ~~strike~~', '  - nested', '', '> quote line', '',
      '```js', `const x${i} = ${i}; // comment`, 'function f() { return "s"; }', '```', '',
      '| a | b |', '|---|---|', `| ${i} | y |`, '', `Inline math $x_${i}^2 + y$ and ![[img${i}.png]] done.`, '', '$$', `\\sum_{k=0}^{${i}} k`, '$$', '');
  }
  return out.slice(0, lines).join('\n');
}

describe('perf (5k-line note)', () => {
  const run = (label, doc, exts) => {
    const parent = document.createElement('div');
    document.body.appendChild(parent);
    const t0 = performance.now();
    const view = new EditorView({ parent, state: EditorState.create({ doc, extensions: [markdown({ base: markdownLanguage, codeLanguages: languages }), ...exts] }) });
    ensureSyntaxTree(view.state, view.state.doc.length, 20000);
    view.dispatch({});
    const create = performance.now() - t0;
    const times = [];
    for (let i = 0; i < 200; i++) {
      const pos = view.state.doc.line(100 + (i % 20)).to;
      const s = performance.now();
      view.dispatch({ changes: { from: pos, insert: 'x' }, selection: { anchor: pos + 1 } });
      times.push(performance.now() - s);
    }
    times.sort((a, b) => a - b);
    const avg = times.reduce((a, b) => a + b, 0) / times.length;
    console.log(`PERF ${label.padEnd(12)} create=${create.toFixed(0)}ms keystroke avg=${avg.toFixed(2)}ms p95=${times[190].toFixed(2)}ms max=${times[199].toFixed(2)}ms`);
    view.destroy();
    return avg;
  };
  it('measures creation and typing', () => {
    const doc = bigNote();
    run('base', doc, [EditorView.lineWrapping]);
    run('+livePreview', doc, [livePreviewExtension()]);
    run('+hashtags', doc, [hashtagsExtension()]);
    run('+codeBlocks', doc, [codeBlocksExtension()]);
    run('+math', doc, [mathExtension()]);
    run('+tables', doc, [tablesExtension()]);
    run('+wiki', doc, [wikiLinksExtension()]);
    run('+embeds', doc, [embedsExtension()]);
    const avg = run('all', doc, [livePreviewExtension(), hashtagsExtension(), codeBlocksExtension(), mathExtension(), tablesExtension(), wikiLinksExtension(), embedsExtension()]);
    expect(avg).toBeLessThan(50);
    const a = doc + '', b = (doc + '').slice();
    const s3 = performance.now();
    let n = 0;
    for (let i = 0; i < 1000; i++) if (a !== b) n++;
    console.log(`PERF dirty compare 5k lines: ${((performance.now() - s3) / 1000).toFixed(5)}ms/compare (${n})`);
  }, 120000);
});

describe('perf: math field on prose lines (5k-line note)', () => {
  it('per-keystroke cost with vs without mathExtension, plus one full findMath scan', () => {
    const doc = bigNote();
    const typing = (exts) => {
      const parent = document.createElement('div');
      document.body.appendChild(parent);
      const view = new EditorView({ parent, state: EditorState.create({ doc, extensions: [markdown({ base: markdownLanguage, codeLanguages: languages }), ...exts] }) });
      ensureSyntaxTree(view.state, view.state.doc.length, 20000);
      view.dispatch({});
      const prose = [];
      for (let n = 1; n <= view.state.doc.lines && prose.length < 300; n++) if (view.state.doc.line(n).text.startsWith('Paragraph')) prose.push(n);
      const times = [];
      for (const n of prose) {
        const pos = view.state.doc.line(n).from;
        const s = performance.now();
        view.dispatch({ changes: { from: pos, insert: 'x' }, selection: { anchor: pos + 1 } });
        times.push(performance.now() - s);
      }
      view.destroy();
      return times.reduce((a, b) => a + b, 0) / times.length;
    };
    typing([]); // warm up
    const base = typing([]);
    const withMath = typing([mathExtension()]);
    const st = EditorState.create({ doc, extensions: [markdown({ base: markdownLanguage })] });
    ensureSyntaxTree(st, st.doc.length, 20000);
    const t0 = performance.now();
    findMath(st);
    console.log(`PERF math prose-line keystroke: base=${base.toFixed(2)}ms +math=${withMath.toFixed(2)}ms; full findMath scan=${(performance.now() - t0).toFixed(1)}ms`);
    expect(withMath - base).toBeLessThan(10); // loose: CI noise; real delta is ~0 (see PERF log)
  }, 120000);
});
