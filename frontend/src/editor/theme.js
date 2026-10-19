// CM6 theme mapped onto the Obsidian Glass tokens (styles/tokens.css) — no literals.
import { EditorView } from '@codemirror/view';

export function editorTheme() {
  return EditorView.theme({
    '&': {
      height: '100%',
      color: 'var(--text)',
      backgroundColor: 'transparent',
      fontFamily: 'var(--font-reading)',
      fontSize: 'var(--fs-18)',
    },
    '&.cm-focused': { outline: 'none' },
    '.cm-scroller': { fontFamily: 'inherit', lineHeight: 'var(--lh-reading)', overflow: 'auto' },
    '.cm-content': { caretColor: 'var(--accent)', paddingBottom: 'var(--sp-10)', fontFamily: 'inherit' },
    '.cm-line': { padding: '0' },
    '.cm-cursor, .cm-dropCursor': { borderLeftColor: 'var(--accent)' },
    '&.cm-focused .cm-selectionBackground, .cm-selectionBackground, ::selection': { backgroundColor: 'var(--accent-tint)' },
    '.cm-activeLine': { backgroundColor: 'transparent' },

    '.cm-md-h': { fontFamily: 'var(--font-display)', fontWeight: '600', letterSpacing: 'var(--tracking-display)', lineHeight: 'var(--lh-tight)' },
    '.cm-md-h1': { fontSize: 'var(--fs-36)', paddingTop: 'var(--sp-4)' },
    '.cm-md-h2': { fontSize: 'var(--fs-28)', paddingTop: 'var(--sp-3)' },
    '.cm-md-h3': { fontSize: 'var(--fs-22)', paddingTop: 'var(--sp-2)' },
    '.cm-md-h4': { fontSize: 'var(--fs-18)' },
    '.cm-md-h5, .cm-md-h6': { fontSize: 'var(--fs-16)', color: 'var(--text-2)' },
    '.cm-md-strong': { fontWeight: '700' },
    '.cm-md-em': { fontStyle: 'italic' },
    '.cm-md-strike': { textDecoration: 'line-through', color: 'var(--text-2)' },
    '.cm-md-code': {
      fontFamily: 'var(--font-mono)', fontSize: '0.88em',
      backgroundColor: 'var(--raised)', borderRadius: '4px', padding: '0 0.3em',
    },
    '.cm-md-link': { color: 'var(--accent-text)', textDecoration: 'underline', textDecorationColor: 'var(--accent-line)' },
    '.cm-md-quote-line': { borderLeft: '3px solid var(--accent-line)', paddingLeft: 'var(--sp-3)', color: 'var(--text-2)' },
    '.cm-md-quote-mark': { color: 'var(--text-3)' },
    '.cm-md-list-mark': { color: 'var(--accent-text)' },
  }, { dark: true });
}

// Distinct read-only look: no caret, default cursor, pointer on links.
export function readOnlyTheme() {
  return EditorView.theme({
    '.cm-content': { cursor: 'default' },
    '.cm-cursor': { display: 'none !important' },
    '.cm-md-link': { cursor: 'pointer' },
  });
}
