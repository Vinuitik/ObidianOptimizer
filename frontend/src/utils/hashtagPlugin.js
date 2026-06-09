import { $node, $remark } from '@milkdown/utils';

// ── Remark plugin: parse #hashtag in the mdast ───────────────────────────────
// Runs once at parse time — zero cost during editing.
// Only matches #word (not #space or # heading — ATX headings are already
// parsed as `heading` nodes before text nodes are visited).

const HASHTAG_RE = /#([A-Za-z0-9_/\-]+)/g;

function hashtagRemarkPlugin() {
  return (tree) => {
    visit(tree, 'text', (node, index, parent) => {
      if (!parent || index == null) return;
      const parts = [];
      let last = 0;
      let match;
      HASHTAG_RE.lastIndex = 0;
      while ((match = HASHTAG_RE.exec(node.value)) !== null) {
        if (match.index > last) {
          parts.push({ type: 'text', value: node.value.slice(last, match.index) });
        }
        parts.push({ type: 'hashtag', tag: match[1], data: { hName: 'span' } });
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

// Minimal visitor — same pattern as wikiLinkPlugin / obsidianImagePlugin
function visit(tree, type, visitor) {
  function walk(node, index, parent) {
    if (node.type === type) visitor(node, index, parent);
    if (node.children) {
      for (let i = node.children.length - 1; i >= 0; i--) {
        walk(node.children[i], i, node);
      }
    }
  }
  walk(tree, null, null);
}

// ── $remark ───────────────────────────────────────────────────────────────────

export const hashtagRemark$ = $remark('hashtag', () => hashtagRemarkPlugin);

// ── $node ─────────────────────────────────────────────────────────────────────

export const hashtagNode$ = $node('hashtag', () => ({
  group: 'inline',
  inline: true,
  atom: true,
  attrs: { tag: { default: '' } },
  toDOM(node) {
    return ['span', { class: 'md-tag', 'data-hashtag': node.attrs.tag }, '#' + node.attrs.tag];
  },
  parseDOM: [{
    tag: 'span[data-hashtag]',
    getAttrs(dom) {
      return { tag: dom.getAttribute('data-hashtag') ?? '' };
    },
  }],
  parseMarkdown: {
    match: node => node.type === 'hashtag',
    runner: (state, node, type) => {
      state.addNode(type, { tag: node.tag });
    },
  },
  toMarkdown: {
    match: node => node.type.name === 'hashtag',
    runner: (state, node) => {
      // Emit verbatim via html node — prevents mdast-util-to-markdown from escaping '#'
      state.addNode('html', undefined, '#' + node.attrs.tag);
    },
  },
}));

// ── Export shape: MilkdownPlugin[] ───────────────────────────────────────────

export const hashtagPlugin = [
  ...hashtagRemark$,
  hashtagNode$,
];
