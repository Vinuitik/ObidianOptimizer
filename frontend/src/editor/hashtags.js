// `#tag` mark decoration (.md-tag). Display only; viewport-only; syntax-tree aware.
// Tag charset matches the markdown-it md_tag rule in utils/markdown.js: letters, digits, `_`, `/`, `-`.
import { Decoration, ViewPlugin } from '@codemirror/view';
import { syntaxTree } from '@codemirror/language';

const tagMark = Decoration.mark({ class: 'md-tag' });
// `#` not preceded by a word char / `#` / `&` (entity) / `/` (URL fragment) / `[` (heading link).
const TAG_RE = /(?<![\w#&/[])#([A-Za-z0-9_/-]*[A-Za-z_/-][A-Za-z0-9_/-]*)/g;
const WIKI_RE = /!?\[\[[^\]\n]*(?:\]\])?/g;
// Nodes whose contents are never tags.
const SKIP = new Set([
  'FencedCode', 'CodeBlock', 'InlineCode', 'CodeText', 'CodeInfo', 'URL', 'Link', 'Image',
  'Autolink', 'HTMLBlock', 'HTMLTag', 'CommentBlock', 'Comment',
]);

function inSkippedNode(tree, pos) {
  for (let n = tree.resolveInner(pos, 1); n; n = n.parent) {
    if (SKIP.has(n.name)) return true;
  }
  return false;
}

export function build(view) {
  const { state } = view;
  const doc = state.doc;
  const tree = syntaxTree(state);
  const ranges = [];
  for (const { from, to } of view.visibleRanges) {
    let pos = from;
    while (pos <= to) {
      const line = doc.lineAt(pos);
      const text = line.text;
      if (text.includes('#')) {
        const wiki = [];
        WIKI_RE.lastIndex = 0;
        for (let m; (m = WIKI_RE.exec(text));) wiki.push([m.index, m.index + m[0].length]);
        TAG_RE.lastIndex = 0;
        for (let m; (m = TAG_RE.exec(text));) {
          const s = m.index, e = s + m[0].length;
          if (wiki.some(([a, b]) => s < b && e > a)) continue;
          if (inSkippedNode(tree, line.from + s)) continue;
          ranges.push(tagMark.range(line.from + s, line.from + e));
        }
      }
      pos = line.to + 1;
    }
  }
  return Decoration.set(ranges, true);
}

const plugin = ViewPlugin.fromClass(class {
  constructor(view) { this.decorations = build(view); this.tree = syntaxTree(view.state); }
  update(u) {
    const tree = syntaxTree(u.state);
    if (u.docChanged || u.viewportChanged || tree !== this.tree) {
      this.decorations = build(u.view);
      this.tree = tree;
    }
  }
}, { decorations: (v) => v.decorations });

export function hashtagsExtension() {
  return [plugin];
}
