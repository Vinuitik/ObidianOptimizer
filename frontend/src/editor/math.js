// KaTeX widgets for $..$, $$..$$ and \[..\]. Source text is never modified; math under the
// selection (or touching it) shows raw. Code (fenced/indented/inline) is masked out first.
import { StateField } from '@codemirror/state';
import { Decoration, EditorView, WidgetType } from '@codemirror/view';
import { syntaxTree } from '@codemirror/language';
import katex from 'katex';

const CACHE_CAP = 500;
const cache = new Map();

// Cached by (displayMode, source). Returns { html } or { error }; never throws.
export function renderMath(src, displayMode) {
  const key = (displayMode ? 'B' : 'I') + src;
  const hit = cache.get(key);
  if (hit) return hit;
  let out;
  try {
    out = { html: katex.renderToString(src, { throwOnError: true, displayMode }) };
  } catch (e) {
    out = { error: String(e?.message ?? e) };
  }
  if (cache.size >= CACHE_CAP) cache.delete(cache.keys().next().value);
  cache.set(key, out);
  return out;
}

const CODE_NODES = new Set(['FencedCode', 'CodeBlock', 'InlineCode', 'CommentBlock', 'HTMLBlock']);
const BLOCK_RE = /\$\$([\s\S]+?)\$\$|\\\[([\s\S]+?)\\\]/g;
// opening $ followed by non-space, closing $ preceded by non-space and not followed by a digit
const INLINE_RE = /(?<![\\$])\$(?!\$)(?=\S)([^$\n]*?\S)\$(?![$\d])/g;

function maskCode(state) {
  const text = state.doc.toString();
  const ranges = [];
  syntaxTree(state).iterate({
    enter(n) {
      if (CODE_NODES.has(n.name)) { ranges.push([n.from, n.to]); return false; }
    },
  });
  if (!ranges.length) return text;
  let out = '';
  let last = 0;
  for (const [f, t] of ranges) {
    out += text.slice(last, f) + text.slice(f, t).replace(/[^\n]/g, ' ');
    last = t;
  }
  return out + text.slice(last);
}

// Pure detection: [{ from, to, src, display }] sorted by position.
export function findMath(state) {
  const text = maskCode(state);
  const doc = state.doc;
  const found = [];
  const taken = [];
  const overlaps = (f, t) => taken.some(([a, b]) => f < b && t > a);

  for (const m of text.matchAll(BLOCK_RE)) {
    const from = m.index;
    const to = from + m[0].length;
    const body = (m[1] ?? m[2]).trim();
    const a = doc.lineAt(from);
    const b = doc.lineAt(to);
    // block only when nothing else shares its lines
    if (text.slice(a.from, from).trim() || text.slice(to, b.to).trim() || !body) continue;
    found.push({ from: a.from, to: b.to, src: body, display: true });
    taken.push([from, to]);
  }
  for (const m of text.matchAll(INLINE_RE)) {
    const from = m.index;
    const to = from + m[0].length;
    if (overlaps(from, to)) continue;
    found.push({ from, to, src: m[1], display: false });
  }
  return found.sort((x, y) => x.from - y.from);
}

class MathWidget extends WidgetType {
  constructor(src, display) { super(); this.src = src; this.display = display; }
  eq(o) { return o.src === this.src && o.display === this.display; }
  get estimatedHeight() { return this.display ? 48 : -1; }
  toDOM(view) {
    const el = document.createElement(this.display ? 'div' : 'span');
    el.className = this.display ? 'cm-math-block' : 'cm-math-inline';
    const r = renderMath(this.src, this.display);
    if (r.html != null) el.innerHTML = r.html;
    else {
      el.classList.add('cm-math-error');
      el.title = r.error;
      el.textContent = this.display ? `$$\n${this.src}\n$$` : `$${this.src}$`;
    }
    el.addEventListener('mousedown', (e) => {
      e.preventDefault();
      view.dispatch({ selection: { anchor: view.posAtDOM(el) }, scrollIntoView: true });
      view.focus();
    });
    return el;
  }
  ignoreEvent() { return true; }
}

function build(state, matches) {
  const decos = [];
  for (const m of matches) {
    if (state.selection.ranges.some((r) => r.from <= m.to && r.to >= m.from)) continue;
    decos.push(Decoration.replace({ widget: new MathWidget(m.src, m.display), block: m.display }).range(m.from, m.to));
  }
  return Decoration.set(decos, true);
}

const mathField = StateField.define({
  create(state) { const matches = findMath(state); return { matches, decos: build(state, matches) }; },
  update(v, tr) {
    const treeChanged = syntaxTree(tr.state) !== syntaxTree(tr.startState);
    if (!tr.docChanged && !tr.selection && !treeChanged) return v;
    const matches = tr.docChanged || treeChanged ? findMath(tr.state) : v.matches;
    return { matches, decos: build(tr.state, matches) };
  },
  provide: (f) => [
    EditorView.decorations.from(f, (v) => v.decos),
    EditorView.atomicRanges.of((view) => view.state.field(f).decos),
  ],
});

export function mathExtension() {
  return [
    mathField,
    EditorView.baseTheme({
      '.cm-math-block': { textAlign: 'center', padding: 'var(--sp-2, 8px) 0', overflowX: 'auto', cursor: 'pointer' },
      '.cm-math-inline': { cursor: 'pointer' },
      '.cm-math-error': { color: 'var(--danger, #e5484d)', fontFamily: 'var(--font-mono)', whiteSpace: 'pre-wrap', textAlign: 'left' },
    }),
  ];
}
