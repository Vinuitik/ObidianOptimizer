// GFM tables (lezer `Table` node) → rendered <table> widget while the selection is outside.
import { StateField } from '@codemirror/state';
import { Decoration, EditorView, WidgetType } from '@codemirror/view';
import { syntaxTree } from '@codemirror/language';

function splitRow(line) {
  let s = line.trim();
  if (s.startsWith('|')) s = s.slice(1);
  if (s.endsWith('|') && !s.endsWith('\\|')) s = s.slice(0, -1);
  const cells = [];
  let cur = '';
  for (let i = 0; i < s.length; i++) {
    if (s[i] === '\\' && s[i + 1] === '|') { cur += '|'; i++; } else if (s[i] === '|') { cells.push(cur.trim()); cur = ''; } else cur += s[i];
  }
  cells.push(cur.trim());
  return cells;
}

export function parseTable(src) {
  const lines = src.split('\n').filter((l) => l.trim());
  if (lines.length < 2) return null;
  const header = splitRow(lines[0]);
  const align = splitRow(lines[1]).map((c) => (/^:-+:$/.test(c) ? 'center' : /^-+:$/.test(c) ? 'right' : /^:-+$/.test(c) ? 'left' : ''));
  return { header, align, rows: lines.slice(2).map(splitRow) };
}

// Minimal inline markdown (code, strong, em, strike) built from DOM nodes — no innerHTML.
const INLINE = /`([^`]+)`|\*\*([^*]+)\*\*|__([^_]+)__|~~([^~]+)~~|\*([^*]+)\*|(?<!\w)_([^_]+)_(?!\w)/g;
export function renderInline(text, parent) {
  let last = 0;
  for (const m of text.matchAll(INLINE)) {
    if (m.index > last) parent.append(text.slice(last, m.index));
    const tag = m[1] != null ? 'code' : m[2] != null || m[3] != null ? 'strong' : m[4] != null ? 'del' : 'em';
    const el = document.createElement(tag);
    el.textContent = m[1] ?? m[2] ?? m[3] ?? m[4] ?? m[5] ?? m[6];
    parent.append(el);
    last = m.index + m[0].length;
  }
  if (last < text.length) parent.append(text.slice(last));
}

class TableWidget extends WidgetType {
  constructor(src) { super(); this.src = src; }
  eq(o) { return o.src === this.src; }
  get estimatedHeight() { return 36 * this.src.split('\n').length; }
  toDOM(view) {
    const wrap = document.createElement('div');
    wrap.className = 'cm-table-wrap';
    const t = parseTable(this.src);
    if (!t) { wrap.textContent = this.src; return wrap; }
    const table = document.createElement('table');
    const row = (cells, tag, parent) => {
      const tr = document.createElement('tr');
      t.header.forEach((_, i) => {
        const c = document.createElement(tag);
        if (t.align[i]) c.style.textAlign = t.align[i];
        renderInline(cells[i] ?? '', c);
        tr.append(c);
      });
      parent.append(tr);
    };
    const thead = document.createElement('thead');
    const tbody = document.createElement('tbody');
    row(t.header, 'th', thead);
    t.rows.forEach((r) => row(r, 'td', tbody));
    table.append(thead, tbody);
    wrap.append(table);
    wrap.addEventListener('mousedown', (e) => {
      e.preventDefault();
      view.dispatch({ selection: { anchor: view.posAtDOM(wrap) }, scrollIntoView: true });
      view.focus();
    });
    return wrap;
  }
  ignoreEvent() { return true; }
}

function build(state) {
  const decos = [];
  const sel = state.selection;
  syntaxTree(state).iterate({
    enter(n) {
      if (n.name === 'Document') return;
      if (n.name === 'Table' && n.node.parent?.name === 'Document') {
        const { from, to } = n;
        if (!sel.ranges.some((r) => r.from <= to && r.to >= from)) {
          decos.push(Decoration.replace({ widget: new TableWidget(state.sliceDoc(from, to)), block: true }).range(from, to));
        }
      }
      return false;
    },
  });
  return Decoration.set(decos, true);
}

const tableField = StateField.define({
  create: build,
  update(v, tr) {
    if (!tr.docChanged && !tr.selection && syntaxTree(tr.state) === syntaxTree(tr.startState)) return v;
    return build(tr.state);
  },
  provide: (f) => [EditorView.decorations.from(f), EditorView.atomicRanges.of((view) => view.state.field(f))],
});

export function tablesExtension() {
  return [
    tableField,
    EditorView.baseTheme({
      '.cm-table-wrap': { overflowX: 'auto', cursor: 'pointer', margin: 'var(--sp-2, 8px) 0' },
      '.cm-table-wrap table': { borderCollapse: 'collapse', width: '100%', fontSize: 'var(--fs-14)' },
      '.cm-table-wrap th, .cm-table-wrap td': { border: '1px solid var(--line-strong)', padding: 'var(--sp-2) var(--sp-3)', textAlign: 'left', color: 'var(--text)' },
      '.cm-table-wrap th': { background: 'var(--raised)', fontFamily: 'var(--font-display)', fontWeight: '600' },
      '.cm-table-wrap tr:nth-child(even) td': { background: 'var(--accent-tint)' },
      '.cm-table-wrap code': { fontFamily: 'var(--font-mono)', fontSize: '0.88em', background: 'var(--raised)', borderRadius: '4px', padding: '0 0.3em' },
    }),
  ];
}
