// Fenced code blocks: block styling via line decorations, dimmed fence lines off-cursor,
// token colours via HighlightStyle. Language grammars come lazily from the markdown()
// `codeLanguages` option in CmEditor.jsx (@codemirror/language-data).
import { Decoration, ViewPlugin } from '@codemirror/view';
import { HighlightStyle, syntaxHighlighting, syntaxTree } from '@codemirror/language';
import { tags as t } from '@lezer/highlight';

const line = (cls) => Decoration.line({ class: cls });
const L_MID = line('cm-md-codeblock');
const L_FIRST = line('cm-md-codeblock cm-md-codeblock-first');
const L_LAST = line('cm-md-codeblock cm-md-codeblock-last');
const L_ONLY = line('cm-md-codeblock cm-md-codeblock-first cm-md-codeblock-last');
const FENCE_DIM = line('cm-md-fence');

// prism-tomorrow palette (literals on purpose: matches the previous Milkdown/prism look).
const highlightStyle = HighlightStyle.define([
  { tag: [t.comment, t.blockComment, t.lineComment, t.docComment], color: '#999' },
  { tag: [t.punctuation, t.separator, t.bracket, t.paren, t.brace, t.squareBracket], color: '#ccc' },
  { tag: [t.tagName, t.propertyName, t.deleted], color: '#e2777a' },
  { tag: [t.number, t.bool, t.null, t.atom], color: '#f08d49' },
  { tag: [t.className, t.typeName], color: '#f8c555' },
  { tag: [t.keyword, t.controlKeyword, t.definitionKeyword, t.moduleKeyword, t.operatorKeyword], color: '#cc99cd' },
  { tag: [t.string, t.character, t.attributeValue, t.inserted, t.regexp], color: '#7ec699' },
  { tag: [t.operator, t.url], color: '#67cdcc' },
  { tag: [t.function(t.variableName), t.function(t.propertyName), t.attributeName], color: '#6196cc' },
]);

function activeLines(state) {
  const set = new Set();
  for (const r of state.selection.ranges) {
    const a = state.doc.lineAt(r.from).number, b = state.doc.lineAt(r.to).number;
    for (let n = a; n <= b; n++) set.add(n);
  }
  return set;
}

export function build(view) {
  const { state } = view;
  const doc = state.doc;
  const active = activeLines(state);
  const out = [];
  const seen = new Set();
  for (const { from, to } of view.visibleRanges) {
    syntaxTree(state).iterate({
      from, to,
      enter(node) {
        if (node.name !== 'FencedCode') return;
        const a = doc.lineAt(node.from).number, b = doc.lineAt(node.to).number;
        const lo = Math.max(a, doc.lineAt(from).number), hi = Math.min(b, doc.lineAt(to).number);
        for (let n = lo; n <= hi; n++) {
          if (seen.has(n)) continue;
          seen.add(n);
          const ln = doc.line(n);
          out.push((a === b ? L_ONLY : n === a ? L_FIRST : n === b ? L_LAST : L_MID).range(ln.from));
          // Opening fence always; closing only if it really is a fence line.
          const isFence = n === a || (n === b && /^\s*(`{3,}|~{3,})\s*$/.test(ln.text));
          if (isFence && !active.has(n)) out.push(FENCE_DIM.range(ln.from));
        }
        return false;
      },
    });
  }
  return Decoration.set(out, true);
}

const plugin = ViewPlugin.fromClass(class {
  constructor(view) { this.decorations = build(view); this.tree = syntaxTree(view.state); }
  update(u) {
    const tree = syntaxTree(u.state);
    if (u.docChanged || u.viewportChanged || u.selectionSet || tree !== this.tree) {
      this.decorations = build(u.view);
      this.tree = tree;
    }
  }
}, { decorations: (v) => v.decorations });

export function codeBlocksExtension() {
  return [plugin, syntaxHighlighting(highlightStyle)];
}
