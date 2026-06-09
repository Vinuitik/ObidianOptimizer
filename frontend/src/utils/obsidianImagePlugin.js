import { $node, $remark } from '@milkdown/utils';

// ── Remark plugin: parse ![[filename]] in the mdast ──────────────────────────

function obsidianImageRemarkPlugin() {
  return (tree) => {
    visit(tree, 'text', (node, index, parent) => {
      if (!parent || index == null) return;
      const RE = /!\[\[([^\]]+?)\]\]/g;
      const parts = [];
      let last = 0;
      let match;
      while ((match = RE.exec(node.value)) !== null) {
        if (match.index > last) {
          parts.push({ type: 'text', value: node.value.slice(last, match.index) });
        }
        parts.push({
          type: 'obsidianImage',
          filename: match[1].trim(),
          data: { hName: 'img' },
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

export const obsidianImageRemark$ = $remark('obsidianImage', () => obsidianImageRemarkPlugin);

// ── $node: renders as <img> in editor, serializes back to ![[...]] ────────────

export const obsidianImageNode$ = $node('obsidian_image', () => ({
  group: 'inline',
  inline: true,
  atom: true,
  attrs: {
    filename: { default: '' },
  },
  toDOM(node) {
    const { filename } = node.attrs;
    return ['img', {
      class: 'embedded-image',
      src: `/api/images/${encodeURIComponent(filename)}`,
      alt: filename,
      'data-obsidian-image': filename,
    }];
  },
  parseDOM: [{
    tag: 'img[data-obsidian-image]',
    getAttrs(dom) {
      return { filename: dom.getAttribute('data-obsidian-image') ?? '' };
    },
  }],
  parseMarkdown: {
    match: node => node.type === 'obsidianImage',
    runner: (state, node, type) => {
      state.addNode(type, { filename: node.filename });
    },
  },
  toMarkdown: {
    match: node => node.type.name === 'obsidian_image',
    runner: (state, node) => {
      // Use 'html' node type to emit verbatim; mdast-util-to-markdown would otherwise escape '['.
      state.addNode('html', undefined, `![[${node.attrs.filename}]]`);
    },
  },
}));

// ── Export shape: MilkdownPlugin[] ───────────────────────────────────────────

export const obsidianImagePlugin = [
  ...obsidianImageRemark$,
  obsidianImageNode$,
];
