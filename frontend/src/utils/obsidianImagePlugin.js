import { $node, $remark } from '@milkdown/utils';

import {
  setPendingBlobs, addPendingBlob, removePendingBlob, resolveMediaSrc, fileTypeFor,
  WHITELISTED_EXTS, WHITELISTED_MIME_TYPES, isWhitelisted, generateFilename, mediaUrl,
} from './media';

// Re-exported so existing importers keep working (helpers live in ./media, Milkdown-free).
export {
  setPendingBlobs, addPendingBlob, removePendingBlob, fileTypeFor,
  WHITELISTED_EXTS, WHITELISTED_MIME_TYPES, isWhitelisted, generateFilename, mediaUrl,
};

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

// ── $node: renders as appropriate element, serializes back to ![[...]] ────────

export const obsidianImageNode$ = $node('obsidian_image', () => ({
  group: 'inline',
  inline: true,
  atom: true,
  attrs: {
    filename: { default: '' },
  },
  toDOM(node) {
    const { filename } = node.attrs;
    const src = resolveMediaSrc(filename);
    const type = fileTypeFor(filename);

    if (type === 'video') {
      return ['video', {
        class: 'embedded-video',
        src,
        controls: '',
        'data-obsidian-image': filename,
      }];
    }
    if (type === 'audio') {
      return ['audio', {
        class: 'embedded-audio',
        src,
        controls: '',
        'data-obsidian-image': filename,
      }];
    }
    if (type === 'pdf') {
      return ['a', {
        class: 'embedded-file',
        href: src,
        target: '_blank',
        rel: 'noopener noreferrer',
        'data-obsidian-image': filename,
      }, `📄 ${filename}`];
    }
    if (type === 'other') {
      return ['a', {
        class: 'embedded-file',
        href: src,
        target: '_blank',
        rel: 'noopener noreferrer',
        'data-obsidian-image': filename,
      }, `📎 ${filename}`];
    }
    // image / svg (default)
    return ['img', {
      class: 'embedded-image',
      src,
      alt: filename,
      'data-obsidian-image': filename,
    }];
  },
  parseDOM: [{
    tag: '[data-obsidian-image]',
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
      state.addNode('html', undefined, `![[${node.attrs.filename}]]`);
    },
  },
}));

// ── Export shape ──────────────────────────────────────────────────────────────

export const obsidianImagePlugin = [
  ...obsidianImageRemark$,
  obsidianImageNode$,
];
