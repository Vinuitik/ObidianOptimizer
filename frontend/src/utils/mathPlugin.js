import { $remark, $node } from '@milkdown/utils';
import katex from 'katex';
import { visit } from 'unist-util-visit';

// ── Remark plugin: parse math syntax into mathInline / mathBlock nodes ────────
//
// Supported syntax (Obsidian-compatible):
//   $...$        inline math
//   $$...$$      block math  (Obsidian standard)
//   \[...\]      block math  (LaTeX display notation)

function remarkMath() {
  return (tree) => {
    try {
      // Walk all 'text' nodes to extract inline $...$ math
      visit(tree, 'text', (node, index, parent) => {
        if (!parent || index == null) return;

        const segments = splitMathText(node.value);
        if (segments.length === 1 && segments[0].type === 'text') return;

        const newNodes = segments.map(seg => {
          if (seg.type === 'text')        return { type: 'text', value: seg.value };
          if (seg.type === 'mathInline')  return { type: 'mathInline', value: seg.value };
          if (seg.type === 'mathBlock')   return { type: 'mathBlock',  value: seg.value, syntax: seg.syntax };
          return { type: 'text', value: seg.value };
        });

        parent.children.splice(index, 1, ...newNodes);
        return index + newNodes.length;
      });

      // Walk 'paragraph' nodes to find standalone $$...$$ and \[...\] blocks
      visit(tree, 'paragraph', (node, index, parent) => {
        if (!parent || index == null) return;
        if (node.children.length !== 1 || node.children[0].type !== 'text') return;

        const raw = node.children[0].value.trim();

        if (raw.startsWith('$$') && raw.endsWith('$$') && raw.length > 4) {
          const content = raw.slice(2, -2).trim();
          parent.children[index] = { type: 'mathBlock', value: content, syntax: '$$' };
          return;
        }

        if (raw.startsWith('\\[') && raw.endsWith('\\]')) {
          const content = raw.slice(2, -2).trim();
          parent.children[index] = { type: 'mathBlock', value: content, syntax: '\\[' };
          return;
        }
      });
    } catch (err) {
      console.error('[mathPlugin] remark transform error — tree left unmodified:', err);
    }
  };
}

// Split a text node value into alternating text / mathInline / mathBlock segments.
function splitMathText(text) {
  const segments = [];
  let remaining = text;

  while (remaining.length > 0) {
    // Try $...$ inline (not $$)
    const inlineMatch = remaining.match(/(?<!\$)\$(?!\$)([^$\n]+?)\$(?!\$)/);
    if (inlineMatch && inlineMatch.index != null) {
      if (inlineMatch.index > 0) {
        segments.push({ type: 'text', value: remaining.slice(0, inlineMatch.index) });
      }
      segments.push({ type: 'mathInline', value: inlineMatch[1] });
      remaining = remaining.slice(inlineMatch.index + inlineMatch[0].length);
      continue;
    }
    break;
  }

  if (remaining.length > 0) segments.push({ type: 'text', value: remaining });
  return segments.length > 0 ? segments : [{ type: 'text', value: text }];
}

// ── Remark plugin $slice ──────────────────────────────────────────────────────

export const mathRemark$ = $remark('math-remark', () => remarkMath);

// ── ProseMirror node: inline math ─────────────────────────────────────────────

export const mathInlineNode$ = $node('mathInline', () => ({
  group: 'inline',
  inline: true,
  atom: true,
  attrs: { value: { default: '' } },
  parseDOM: [{ tag: 'span.math-inline', getAttrs: dom => ({ value: dom.getAttribute('data-math') ?? '' }) }],
  toDOM(node) {
    const span = document.createElement('span');
    span.className = 'math-inline';
    span.setAttribute('data-math', node.attrs.value);
    span.setAttribute('contenteditable', 'false');
    try {
      span.innerHTML = katex.renderToString(node.attrs.value, {
        throwOnError: false,
        displayMode: false,
      });
    } catch {
      span.textContent = `$${node.attrs.value}$`;
    }
    return span;
  },
  parseMarkdown: {
    match: node => node.type === 'mathInline',
    runner: (state, node, type) => {
      state.addNode(type, { value: node.value });
    },
  },
  toMarkdown: {
    match: node => node.type.name === 'mathInline',
    runner: (state, node) => {
      state.addNode('html', undefined, `$${node.attrs.value}$`);
    },
  },
}));

// ── ProseMirror node: block math ──────────────────────────────────────────────

export const mathBlockNode$ = $node('mathBlock', () => ({
  group: 'block',
  atom: true,
  attrs: {
    value:  { default: '' },
    syntax: { default: '$$' },   // '$$' or '\\[' — preserved on serialize
  },
  parseDOM: [{
    tag: 'div.math-block',
    getAttrs: dom => ({
      value:  dom.getAttribute('data-math') ?? '',
      syntax: dom.getAttribute('data-syntax') ?? '$$',
    }),
  }],
  toDOM(node) {
    const div = document.createElement('div');
    div.className = 'math-block';
    div.setAttribute('data-math',   node.attrs.value);
    div.setAttribute('data-syntax', node.attrs.syntax);
    div.setAttribute('contenteditable', 'false');
    try {
      div.innerHTML = katex.renderToString(node.attrs.value, {
        throwOnError: false,
        displayMode: true,
      });
    } catch {
      const pre = document.createElement('pre');
      pre.textContent = node.attrs.syntax === '\\['
        ? `\\[\n${node.attrs.value}\n\\]`
        : `$$\n${node.attrs.value}\n$$`;
      div.appendChild(pre);
    }
    return div;
  },
  parseMarkdown: {
    match: node => node.type === 'mathBlock',
    runner: (state, node, type) => {
      state.addNode(type, {
        value:  node.value,
        syntax: node.syntax ?? '$$',
      });
    },
  },
  toMarkdown: {
    match: node => node.type.name === 'mathBlock',
    runner: (state, node) => {
      const { value, syntax } = node.attrs;
      const raw = syntax === '\\['
        ? `\\[\n${value}\n\\]`
        : `$$\n${value}\n$$`;
      state.addNode('html', undefined, raw);
    },
  },
}));

// ── Assembled plugin ──────────────────────────────────────────────────────────

export const mathPlugin = [...mathRemark$, mathInlineNode$, mathBlockNode$];
