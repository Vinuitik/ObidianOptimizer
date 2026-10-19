import { useEffect, useRef } from 'react';
import { Compartment, EditorState } from '@codemirror/state';
import { EditorView, keymap } from '@codemirror/view';
import { defaultKeymap, history, historyKeymap, indentWithTab } from '@codemirror/commands';
import { markdown, markdownLanguage } from '@codemirror/lang-markdown';
import { languages } from '@codemirror/language-data';
import useStore from '../store/useStore';
import { splitFrontmatter } from '../utils/frontmatter';
import EditorErrorBoundary from '../components/organisms/EditorErrorBoundary';
import { editorTheme, readOnlyTheme } from './theme';
import { livePreviewExtension } from './livePreview';
import styles from './CmEditor.module.css';

const DEBOUNCE_MS = 120;

const readOnlyCompartment = new Compartment();
const readOnlyExt = (isMutable) => (isMutable
  ? [EditorState.readOnly.of(false), EditorView.editable.of(true)]
  : [EditorState.readOnly.of(true), EditorView.editable.of(false), readOnlyTheme()]);

// Feature extensions. Each W-milestone agent adds exactly ONE line here.
export function featureExtensions(/* opts */) {
  return [
    livePreviewExtension(),
    // W1: ...wikiLinksExtension(opts),
    // W2: ...embedsExtension(opts),
    // W3: ...mathExtension(opts),
    // W3: ...tablesExtension(opts),
    // W4: ...hashtagsExtension(opts),
    // W4: ...codeBlocksExtension(opts),
  ];
}

export function baseExtensions({ onDocChange, onFlush, isMutable }) {
  return [
    history(),
    keymap.of([
      // Flush the debounced body before Ctrl+S's window handler saves; returns false so the
      // event keeps bubbling to SplitLayout.
      { key: 'Mod-s', run: () => { onFlush(); return false; } },
      indentWithTab,
      ...defaultKeymap,
      ...historyKeymap,
    ]),
    markdown({ base: markdownLanguage, codeLanguages: languages }),
    EditorView.lineWrapping,
    editorTheme(),
    ...featureExtensions({ isMutable }),
    readOnlyCompartment.of(readOnlyExt(isMutable)),
    EditorView.updateListener.of((u) => { if (u.docChanged) onDocChange(u.view); }),
    EditorView.domEventHandlers({
      blur: () => { onFlush(); return false; },
      click: (e) => {
        // Links: plain click in read-only, Ctrl/Cmd+click while editing.
        const el = e.target.closest?.('[data-href]');
        if (!el) return false;
        const editing = useStore.getState().isMutable;
        if (editing && !(e.ctrlKey || e.metaKey)) return false;
        e.preventDefault();
        window.open(el.getAttribute('data-href'), '_blank', 'noopener,noreferrer');
        return true;
      },
    }),
  ];
}

function CmEditorInner({ currentNotePath, editorResetKey }) {
  const hostRef = useRef(null);
  const viewRef = useRef(null);
  const isMutable = useStore(s => s.isMutable);

  // Create the view once per note / reset — never per keystroke.
  useEffect(() => {
    const body = splitFrontmatter(useStore.getState().pendingRaw).body;
    let timer = null;
    let view = null;
    const flush = () => {
      if (timer == null) return;
      clearTimeout(timer);
      timer = null;
      useStore.getState().updatePending(view.state.doc.toString());
    };
    const onDocChange = () => {
      if (timer != null) clearTimeout(timer);
      timer = setTimeout(flush, DEBOUNCE_MS);
    };
    view = new EditorView({
      parent: hostRef.current,
      state: EditorState.create({
        doc: body,
        extensions: baseExtensions({ onDocChange, onFlush: flush, isMutable: useStore.getState().isMutable }),
      }),
    });
    viewRef.current = view;
    return () => {
      // Pending timer is dropped, not flushed: on cancel the store was just reset and a late
      // flush would resurrect discarded text. Blur/Ctrl+S already flush before any switch.
      if (timer != null) clearTimeout(timer);
      view.destroy();
      viewRef.current = null;
    };
  }, [currentNotePath, editorResetKey]);

  useEffect(() => {
    viewRef.current?.dispatch({ effects: readOnlyCompartment.reconfigure(readOnlyExt(isMutable)) });
  }, [isMutable]);

  return <div ref={hostRef} className={`${styles.host} ${isMutable ? '' : styles.readonly}`} />;
}

export default function CmEditor() {
  const currentNotePath = useStore(s => s.currentNotePath);
  const editorResetKey  = useStore(s => s.editorResetKey);

  if (!currentNotePath) {
    return (
      <div className={styles.empty}>
        <span className={styles.emptyIcon}>📄</span>
        <span className={styles.emptyText}>Select a note to get started</span>
      </div>
    );
  }

  // Attachments (📎, paste, drop) layout slot: W2 adds its UI inside this wrapper.
  return (
    <div className={styles.wrapper}>
      <EditorErrorBoundary>
        <CmEditorInner currentNotePath={currentNotePath} editorResetKey={editorResetKey} />
      </EditorErrorBoundary>
    </div>
  );
}
