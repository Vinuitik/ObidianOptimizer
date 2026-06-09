import { $prose } from '@milkdown/utils';
import { Plugin, PluginKey } from '@milkdown/prose/state';
import { Decoration, DecorationSet } from '@milkdown/prose/view';

// ── Syntax characters shown when cursor is inside a mark ─────────────────────
// Values stored as data attributes; CSS ::before / ::after renders them.
// Using inline decorations with nodeName:'span' avoids inserting non-editable
// DOM widget nodes, which caused browsers to swallow spaces typed near the
// mark boundary.

const MARK_SYNTAX = {
  em:     { open: '*',  close: '*'  },
  strong: { open: '**', close: '**' },
  code:   { open: '`',  close: '`'  },
};

// ── Find the continuous extent of markType around cursorPos in the doc ────────

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
      if (cursorPos <= current.to) return;
      current = null;
    }
  });

  if (current && cursorPos >= current.from && cursorPos <= current.to) {
    return current;
  }
  return null;
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

    // A single inline decoration wrapping the mark extent in a <span>.
    // CSS ::before / ::after on the span display the syntax characters.
    // No non-editable DOM nodes are inserted, so cursor and space input work normally.
    decorations.push(
      Decoration.inline(extent.from, extent.to, {
        nodeName: 'span',
        class: `pm-active-mark pm-${markName}`,
        'data-md-open':  syntax.open,
        'data-md-close': syntax.close,
      }),
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
