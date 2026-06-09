# WYSIWYG Editor — Milkdown Integration Notes

Written after a failed first attempt. Keep this for the next session.

---

## What we confirmed WORKS

- Build compiled cleanly (no module errors)
- Content loading: `rawLen=204 bodyLen=144` — `openNote` → `pendingRaw` → `splitFrontmatter` pipeline is correct
- Milkdown initialised: `loading: true → false` — the editor `.create()` succeeded
- All imports resolve: `@milkdown/core`, `@milkdown/react`, `@milkdown/preset-commonmark`,
  `@milkdown/plugin-history`, `@milkdown/plugin-listener`, `@milkdown/utils`,
  `@milkdown/prose/inputrules` — all valid in v7.21.2
- The `$node`, `$inputRule`, `$remark` plugin API works as expected
- `wikiLinkPlugin = [...wikiLinkRemark$, wikiLinkNode$, wikiLinkInputRule$]` is the correct export shape
  (`Editor.use()` accepts `MilkdownPlugin | MilkdownPlugin[]`)

## What was broken — display

Milkdown initialised but rendered no visible text. Root cause: **CSS height chain**.

Milkdown's DOM structure inside `[data-milkdown-root]`:
```
[data-milkdown-root]       ← Milkdown React component; no styles
  └─ div.milkdown          ← Milkdown core adds this class + wraps the view
       └─ div.ProseMirror.editor   ← ProseMirror EditorView; "editor" class added by Milkdown
```

Our wrapper had `flex: 1` but the intermediate divs (`.milkdown`, `[data-milkdown-root]`) had
no height or flex propagation. `min-height: 100%` on `.ProseMirror` was computing as 0
because the parent chain collapsed.

### Fix for next time

Apply `markdown-body` styling directly on `.ProseMirror` not on the wrapper div,
and make the entire height chain explicit:

```css
.milkdownWrapper {
  flex: 1;
  overflow-y: auto;
  padding: var(--sp-5) var(--sp-6);
  display: flex;
  flex-direction: column;
}

.milkdownWrapper :global([data-milkdown-root]),
.milkdownWrapper :global(.milkdown) {
  flex: 1;
  display: flex;
  flex-direction: column;
}

/* Apply markdown-body styles here, not on the wrapper */
.milkdownWrapper :global(.ProseMirror) {
  flex: 1;
  outline: none;
  caret-color: var(--color-accent);
  font-family: var(--font-body);
  font-size: var(--text-base);
  line-height: var(--leading-normal);
  color: var(--color-text);
}
```

Do NOT rely on a parent `markdown-body` class for text colour — Milkdown may reset it.
Style `.ProseMirror` directly.

---

## Milkdown v7 API cheat-sheet

### React wiring
```jsx
<MilkdownProvider>          // context provider — no DOM
  <MilkdownEditorInner />   // calls useEditor() + useInstance()
    <Milkdown />            // renders [data-milkdown-root] div
</MilkdownProvider>
```

### useEditor
```js
useEditor((root) =>         // root = HTMLElement for [data-milkdown-root]
  Editor.make()
    .config((ctx) => {
      ctx.set(rootCtx, root);
      ctx.set(defaultValueCtx, markdownString);
      ctx.update(editorViewOptionsCtx, prev => ({ ...prev, editable: () => true }));
      ctx.get(listenerCtx).markdownUpdated((_, md) => onChange(md));
    })
    .use(commonmark)
    .use(history)
    .use(listener)
    .use(myCustomPlugin)    // MilkdownPlugin | MilkdownPlugin[]
);
```

### Toggling editable after mount (without remount)
```js
const [loading, getInstance] = useInstance();
useEffect(() => {
  if (loading) return;
  getInstance()?.action((ctx) => {
    ctx.get(editorViewCtx)?.setProps({ editable: () => isEditable });
  });
}, [isEditable, loading]);
```

### Switching notes without state bleed
Put `key={currentNotePath}` on `MilkdownEditorInner` — React remounts the component
(and thus the editor) cleanly whenever the note changes.

---

## Custom node (wiki links) — $node API

```js
import { $node, $inputRule, $remark } from '@milkdown/utils';
import { InputRule } from '@milkdown/prose/inputrules';

const wikiLinkNode$ = $node('wiki_link', () => ({
  group: 'inline',
  inline: true,
  atom: true,           // treated as a single unit by ProseMirror
  attrs: { target: { default: '' }, display: { default: null } },
  toDOM(node) {
    return ['span', {
      class: 'wiki-link',
      'data-wiki-link': node.attrs.target,
      'data-wiki-display': node.attrs.display ?? '',
    }, node.attrs.display || node.attrs.target];
  },
  parseDOM: [{ tag: 'span[data-wiki-link]', getAttrs(dom) {
    return { target: dom.getAttribute('data-wiki-link') ?? '', display: dom.getAttribute('data-wiki-display') || null };
  }}],
  parseMarkdown: {
    match: node => node.type === 'wikiLink',   // custom mdast type from remark plugin
    runner: (state, node, type) => state.addNode(type, { target: node.target, display: node.display }),
  },
  toMarkdown: {
    match: node => node.type.name === 'wiki_link',
    runner: (state, node) => {
      const { target, display } = node.attrs;
      // addNode('text') outputs raw text into the markdown serialiser
      state.addNode('text', undefined, display ? `[[${target}|${display}]]` : `[[${target}]]`);
    },
  },
}));
```

### $remark — inject unified plugin
```js
const wikiLinkRemark$ = $remark('wikiLink', () => wikiLinkRemarkPlugin, {});
// wikiLinkRemarkPlugin = () => (tree) => { /* transform mdast */ }
```

### $inputRule
```js
const wikiLinkInputRule$ = $inputRule(ctx =>
  new InputRule(
    /\[\[([^\]|]+?)(?:\|([^\]]+?))?\]\]$/,
    (state, match, start, end) =>
      state.tr.replaceWith(start, end, wikiLinkNode$.type(ctx).create({
        target: match[1].trim(),
        display: match[2]?.trim() ?? null,
      }))
  )
);
```

### Export shape
```js
export const wikiLinkPlugin = [
  ...wikiLinkRemark$,   // [optionsCtx, remarkPlugin]
  wikiLinkNode$,
  wikiLinkInputRule$,
];
```

---

## NodeSchema type (from @milkdown/transformer)

```ts
interface NodeSchema extends NodeSpec {   // NodeSpec from ProseMirror
  toMarkdown: {
    match: (node: Node) => boolean;
    runner: (state: SerializerState, node: Node) => void;
  };
  parseMarkdown: {
    match: (node: MarkdownNode) => boolean;
    runner: (state: ParserState, node: MarkdownNode, proseType: NodeType) => void;
  };
}
```

SerializerState key methods: `openNode`, `closeNode`, `addNode(type, children?, value?, props?)`, `next(nodes)`.
ParserState key methods: `addNode(type, attrs?, content?)`, `addText(text)`, `openNode`, `closeNode`.

---

## Frontmatter strategy (confirmed correct)

Strip before passing to Milkdown, re-attach on sync:
```js
const { frontmatter, body } = splitFrontmatter(pendingRaw);
// pass body to Milkdown
// on change: updatePending(joinFrontmatter(frontmatter, newBody))
// on sync: computeHunks(currentNoteRaw, pendingRaw) → PATCH
```

`splitFrontmatter`, `joinFrontmatter`, `parseFrontmatterFields` are in `frontend/src/utils/frontmatter.js`
(removed by rollback — files to recreate when resuming).

---

## Files to recreate (removed by rollback)

| File | Status |
|---|---|
| `frontend/src/utils/frontmatter.js` | recreate — logic is correct |
| `frontend/src/utils/wikiLinkPlugin.js` | recreate — logic is correct, no changes needed |
| `frontend/package.json` changes | re-run `npm install @milkdown/core @milkdown/react @milkdown/preset-commonmark @milkdown/plugin-history @milkdown/plugin-listener` |

## Store changes to re-apply (useStore.js)

Add: `isMutable: true`, `pendingRaw: ''`, `pendingTitle: ''`  
Add: `toggleMutable`, `updatePending(raw)`, `setPendingTitle(title)`, `syncNote()`  
Modify `openNote`: set `pendingRaw = raw`, `pendingTitle = basename`, add unsaved-guard confirm  
Remove: `startEdit`, `cancelEdit`, `saveNote`
