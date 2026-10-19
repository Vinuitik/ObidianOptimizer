// Live-preview decorations: hide markdown syntax markers except on lines touching a selection.
// Syntax-tree (lezer) based, viewport-only. Replace decorations are exposed via atomicRanges
// so the cursor never lands inside a hidden marker.
import { Decoration, ViewPlugin, EditorView } from '@codemirror/view';
import { syntaxTree } from '@codemirror/language';

const hide = Decoration.replace({});
const headingLines = {};
const headingLine = (n) => (headingLines[n] ??= Decoration.line({ class: `cm-md-h cm-md-h${n}` }));
const quoteLine = Decoration.line({ class: 'cm-md-quote-line' });
const strongMark = Decoration.mark({ class: 'cm-md-strong' });
const emMark = Decoration.mark({ class: 'cm-md-em' });
const strikeMark = Decoration.mark({ class: 'cm-md-strike' });
const codeMark = Decoration.mark({ class: 'cm-md-code' });
const quoteMarkerMark = Decoration.mark({ class: 'cm-md-quote-mark' });
const listMarkerMark = Decoration.mark({ class: 'cm-md-list-mark' });
const linkMark = (href) => Decoration.mark({ class: 'cm-md-link', attributes: { 'data-href': href } });

function activeLines(state) {
  const set = new Set();
  for (const r of state.selection.ranges) {
    const a = state.doc.lineAt(r.from).number;
    const b = state.doc.lineAt(r.to).number;
    for (let n = a; n <= b; n++) set.add(n);
  }
  return set;
}

function build(view) {
  const { state } = view;
  const active = activeLines(state);
  const all = [];     // every decoration
  const atomic = [];  // replace decorations only
  const doc = state.doc;
  const isActive = (pos) => active.has(doc.lineAt(pos).number);
  const addHide = (from, to) => {
    if (to <= from) return;
    const r = hide.range(from, to);
    all.push(r);
    atomic.push(r);
  };

  for (const { from, to } of view.visibleRanges) {
    syntaxTree(state).iterate({
      from, to,
      enter(node) {
        const name = node.name;
        const act = isActive(node.from);
        const m = /^ATXHeading([1-6])$/.exec(name);
        if (m) {
          all.push(headingLine(+m[1]).range(doc.lineAt(node.from).from));
          return;
        }
        if (name === 'HeaderMark') {
          if (!act && node.node.parent && /^ATXHeading/.test(node.node.parent.name)) {
            // hide the #'s plus the single space after them
            const after = doc.sliceString(node.to, node.to + 1) === ' ' ? node.to + 1 : node.to;
            addHide(node.from, after);
          }
          return;
        }
        if (name === 'Blockquote') {
          for (let p = doc.lineAt(node.from).number; p <= doc.lineAt(node.to).number; p++) {
            all.push(quoteLine.range(doc.line(p).from));
          }
          return;
        }
        if (name === 'QuoteMark') { all.push(quoteMarkerMark.range(node.from, node.to)); return; }
        if (name === 'ListMark') { all.push(listMarkerMark.range(node.from, node.to)); return; }
        if (name === 'StrongEmphasis') { all.push(strongMark.range(node.from, node.to)); return; }
        if (name === 'Emphasis') { all.push(emMark.range(node.from, node.to)); return; }
        if (name === 'Strikethrough') { all.push(strikeMark.range(node.from, node.to)); return; }
        if (name === 'InlineCode') { all.push(codeMark.range(node.from, node.to)); return; }
        if (name === 'EmphasisMark' || name === 'StrikethroughMark' || name === 'CodeMark') {
          // CodeMark also appears in fenced blocks (on their own lines) — only hide inline ones.
          const parent = node.node.parent?.name;
          if (!act && (name !== 'CodeMark' || parent === 'InlineCode')) addHide(node.from, node.to);
          return;
        }
        if (name === 'Link') {
          const urlNode = node.node.getChild('URL');
          const marks = node.node.getChildren('LinkMark');
          if (!urlNode || marks.length < 2) return;
          const textFrom = marks[0].to;
          const textTo = marks[1].from;
          const href = doc.sliceString(urlNode.from, urlNode.to);
          if (textTo > textFrom) all.push(linkMark(href).range(textFrom, textTo));
          if (!act) {
            addHide(node.from, textFrom);   // "["
            addHide(textTo, node.to);       // "](url ...)"
          }
        }
      },
    });
  }
  return { deco: Decoration.set(all, true), atomic: Decoration.set(atomic, true) };
}

const plugin = ViewPlugin.fromClass(class {
  constructor(view) {
    const b = build(view);
    this.decorations = b.deco;
    this.atomic = b.atomic;
    this.tree = syntaxTree(view.state);
  }
  update(u) {
    const tree = syntaxTree(u.state);
    if (u.docChanged || u.viewportChanged || u.selectionSet || tree !== this.tree) {
      const b = build(u.view);
      this.decorations = b.deco;
      this.atomic = b.atomic;
      this.tree = tree;
    }
  }
}, {
  decorations: (v) => v.decorations,
  provide: (p) => EditorView.atomicRanges.of((view) => view.plugin(p)?.atomic ?? Decoration.none),
});

export function livePreviewExtension() {
  return [plugin];
}
