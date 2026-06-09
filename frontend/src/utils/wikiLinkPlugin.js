import { $node, $inputRule, $remark } from '@milkdown/utils';
import { InputRule } from '@milkdown/prose/inputrules';

// ── Remark plugin: parse [[target]] / [[target|display]] in the mdast ─────────

function wikiLinkRemarkPlugin() {
  return (tree) => {
    visit(tree, 'text', (node, index, parent) => {
      if (!parent || index == null) return;
      const WIKI = /\[\[([^\]|]+?)(?:\|([^\]]+?))?\]\]/g;
      const parts = [];
      let last = 0;
      let match;
      while ((match = WIKI.exec(node.value)) !== null) {
        if (match.index > last) {
          parts.push({ type: 'text', value: node.value.slice(last, match.index) });
        }
        parts.push({
          type: 'wikiLink',
          target: match[1].trim(),
          display: match[2]?.trim() ?? null,
          data: { hName: 'span' },
        });
        last = match.index + match[0].length;
      }
      if (parts.length === 0) return;
      if (last < node.value.length) {
        parts.push({ type: 'text', value: node.value.slice(last) });
      }
      parent.children.splice(index, 1, ...parts);
    });
  };
}

// Minimal visitor — avoids pulling in the full unist-util-visit dep
function visit(tree, type, visitor) {
  function walk(node, index, parent) {
    if (node.type === type) visitor(node, index, parent);
    if (node.children) {
      // iterate backwards so splicing doesn't break indices
      for (let i = node.children.length - 1; i >= 0; i--) {
        walk(node.children[i], i, node);
      }
    }
  }
  walk(tree, null, null);
}

// ── $remark: inject the remark plugin into Milkdown's pipeline ────────────────

export const wikiLinkRemark$ = $remark('wikiLink', () => wikiLinkRemarkPlugin);

// ── $node: ProseMirror node type for wiki links ───────────────────────────────

export const wikiLinkNode$ = $node('wiki_link', () => ({
  group: 'inline',
  inline: true,
  atom: true,
  attrs: {
    target:  { default: '' },
    display: { default: null },
  },
  toDOM(node) {
    const label = node.attrs.display || node.attrs.target;
    return ['span', {
      class: 'wiki-link',
      'data-wiki-link': node.attrs.target,
      'data-wiki-display': node.attrs.display ?? '',
    }, label];
  },
  parseDOM: [{
    tag: 'span[data-wiki-link]',
    getAttrs(dom) {
      return {
        target:  dom.getAttribute('data-wiki-link') ?? '',
        display: dom.getAttribute('data-wiki-display') || null,
      };
    },
  }],
  parseMarkdown: {
    match: node => node.type === 'wikiLink',
    runner: (state, node, type) => {
      state.addNode(type, { target: node.target, display: node.display ?? null });
    },
  },
  toMarkdown: {
    match: node => node.type.name === 'wiki_link',
    runner: (state, node) => {
      const { target, display } = node.attrs;
      const raw = display ? `[[${target}|${display}]]` : `[[${target}]]`;
      state.addNode('text', undefined, raw);
    },
  },
}));

// ── $inputRule: typing [[...]] creates the node inline ───────────────────────

export const wikiLinkInputRule$ = $inputRule(ctx =>
  new InputRule(
    /\[\[([^\]|]+?)(?:\|([^\]]+?))?\]\]$/,
    (state, match, start, end) => {
      const type = wikiLinkNode$.type(ctx);
      return state.tr.replaceWith(start, end, type.create({
        target:  match[1].trim(),
        display: match[2]?.trim() ?? null,
      }));
    }
  )
);

// ── Export shape: MilkdownPlugin[] ───────────────────────────────────────────

export const wikiLinkPlugin = [
  ...wikiLinkRemark$,
  wikiLinkNode$,
  wikiLinkInputRule$,
];
