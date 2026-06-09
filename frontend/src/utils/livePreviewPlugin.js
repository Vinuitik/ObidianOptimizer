import { $prose } from '@milkdown/utils';
import { Plugin, PluginKey } from '@milkdown/prose/state';
import { Decoration, DecorationSet } from '@milkdown/prose/view';

// ── Syntax characters to show when cursor is inside a mark ───────────────────

const MARK_SYNTAX = {
  em:     { open: '*',  close: '*'  },
  strong: { open: '**', close: '**' },
  code:   { open: '`',  close: '`'  },
};

// ── Find the continuous extent of markType around cursorPos in the doc ────────
// Iterates the parent paragraph's children — O(children count), not O(doc size).

function markExtent(doc, cursorPos, markType) {
  const $pos = doc.resolve(cursorPos);
  const parent = $pos.parent;
  const parentStart = cursorPos - $pos.parentOffset;

  let current = null;

  parent.forEach((child, childOffset) => {
    const nodeFrom = parentStart + childOffset;
    const nodeTo   = nodeFrom + child.nodeSize;
    const hasMark  = child.isText && markType.isInSet(child.marks);

    if (hasMark) {
      if (!current) current = { from: nodeFrom, to: nodeTo };
      else current.to = nodeTo;
    } else if (current) {
      // gap — stop extending; check if cursor was already inside
      if (cursorPos <= current.to) return; // will be picked up below
      current = null;
    }
  });

  if (current && cursorPos >= current.from && cursorPos <= current.to) {
    return current;
  }
  return null;
}

// ── Widget factory: a small <span> with the syntax character ─────────────────

function syntaxWidget(text) {
  return () => {
    const span = document.createElement('span');
    span.className = 'pm-src-char';
    span.textContent = text;
    return span;
  };
}

// ── Build the full decoration set for the current state ──────────────────────

function buildDecorations(state) {
  const decorations = [];
  const { selection, schema, doc } = state;
  const { from } = selection;
  const $from = doc.resolve(from);

  for (const [markName, syntax] of Object.entries(MARK_SYNTAX)) {
    const markType = schema.marks[markName];
    if (!markType) continue;
    if (!markType.isInSet($from.marks())) continue;

    const extent = markExtent(doc, from, markType);
    if (!extent) continue;

    decorations.push(
      Decoration.widget(extent.from, syntaxWidget(syntax.open),  { side: -1, key: `${markName}-open`  }),
      Decoration.widget(extent.to,   syntaxWidget(syntax.close), { side:  1, key: `${markName}-close` }),
      Decoration.inline(extent.from, extent.to, { class: `pm-active-mark pm-${markName}` }),
    );
  }

  return DecorationSet.create(doc, decorations);
}

// ── ProseMirror plugin wired into Milkdown via $prose ────────────────────────

const livePreviewKey = new PluginKey('live-preview');

export const livePreviewPlugin = $prose(() =>
  new Plugin({
    key: livePreviewKey,
    state: {
      init(_, state) {
        return buildDecorations(state);
      },
      apply(tr, old, _, newState) {
        // Only rebuild when selection or document changes
        if (!tr.selectionSet && !tr.docChanged) return old;
        return buildDecorations(newState);
      },
    },
    props: {
      decorations(state) {
        return livePreviewKey.getState(state);
      },
    },
  })
);
