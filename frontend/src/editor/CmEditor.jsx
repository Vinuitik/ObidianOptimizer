import { useEffect, useLayoutEffect, useRef, useState } from 'react';
import { Compartment, EditorState } from '@codemirror/state';
import { EditorView, keymap } from '@codemirror/view';
import { defaultKeymap, history, historyKeymap, indentWithTab } from '@codemirror/commands';
import { markdown, markdownLanguage } from '@codemirror/lang-markdown';
import { languages } from '@codemirror/language-data';
import useStore, { setEditorFlush } from '../store/useStore';
import { splitFrontmatter } from '../utils/frontmatter';
import EditorErrorBoundary from '../components/organisms/EditorErrorBoundary';
import { editorTheme, readOnlyTheme } from './theme';
import { livePreviewExtension } from './livePreview';
import { wikiLinksExtension } from './wikiLinks';
import { embedsExtension, insertFiles } from './embeds';
import { setPendingBlobs, WHITELISTED_EXTS } from '../utils/media';
import ImageLightbox from '../components/molecules/ImageLightbox';
import { mathExtension } from './math';
import { tablesExtension } from './tables';
import { hashtagsExtension } from './hashtags';
import { codeBlocksExtension } from './codeBlocks';
import styles from './CmEditor.module.css';

const DEBOUNCE_MS = 120;

// CM6 would normalise CRLF to LF; keep the note's separator so saves round-trip byte-identically
// (computeHunks must only see real edits). CRLF only if EVERY newline is CRLF; mixed/LF → default.
export function detectLineSeparator(body) {
  const lf = (body.match(/\n/g) || []).length;
  if (lf === 0) return null;
  const crlf = (body.match(/\r\n/g) || []).length;
  return crlf === lf ? '\r\n' : null;
}

const readOnlyCompartment = new Compartment();
const readOnlyExt = (isMutable) => (isMutable
  ? [EditorState.readOnly.of(false), EditorView.editable.of(true)]
  : [EditorState.readOnly.of(true), EditorView.editable.of(false), readOnlyTheme()]);

// Feature extensions (one line per feature; see editor/FLOWS.md).
export function featureExtensions(opts = {}) {
  return [
    livePreviewExtension(),
    ...wikiLinksExtension(opts),
    ...embedsExtension(opts),
    ...mathExtension(),
    ...tablesExtension(),
    ...hashtagsExtension(),
    ...codeBlocksExtension(),
  ];
}

export function baseExtensions({ onDocChange, onFlush, isMutable, ...featureOpts }) {
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
    ...featureExtensions({ isMutable, ...featureOpts }),
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

const ACCEPT = [...WHITELISTED_EXTS].join(',');

function CmEditorInner({ currentNotePath, editorResetKey }) {
  const hostRef = useRef(null);
  const viewRef = useRef(null);
  const isMutable = useStore(s => s.isMutable);
  const pendingFiles = useStore(s => s.pendingFiles);
  const fileInputRef = useRef(null);
  const [zoom, setZoom] = useState(null);
  const [isDragOver, setIsDragOver] = useState(false);

  // Blob registry mirrors the active tab's pending files (set before the view is created).
  useLayoutEffect(() => { setPendingBlobs(pendingFiles); }, [pendingFiles]);

  // Create the view once per note / reset — never per keystroke.
  useEffect(() => {
    const body = splitFrontmatter(useStore.getState().pendingRaw).body;
    const sep = detectLineSeparator(body);
    let timer = null;
    let view = null;
    const flush = () => {
      if (timer == null) return;
      clearTimeout(timer);
      timer = null;
      useStore.getState().updatePending(view.state.sliceDoc());
    };
    const onDocChange = () => {
      if (timer != null) clearTimeout(timer);
      timer = setTimeout(flush, DEBOUNCE_MS);
    };
    view = new EditorView({
      parent: hostRef.current,
      state: EditorState.create({
        doc: body,
        extensions: [
          ...(sep ? [EditorState.lineSeparator.of(sep)] : []),
          baseExtensions({ onDocChange, onFlush: flush, isMutable: useStore.getState().isMutable,
            onImageClick: (src, alt) => setZoom({ src, alt }),
            onDragOver: setIsDragOver,
          }),
        ],
      }),
    });
    viewRef.current = view;
    setEditorFlush(flush);   // store.flushSave() pulls the debounced text through this
    return () => {
      setEditorFlush(null);
      // Pending timer is dropped, not flushed: store.flushSave() (tab/note switch, toggle,
      // title rename) already pulled the text through setEditorFlush before this unmount.
      if (timer != null) clearTimeout(timer);
      view.destroy();
      viewRef.current = null;
    };
  }, [currentNotePath, editorResetKey]);

  useEffect(() => {
    viewRef.current?.dispatch({ effects: readOnlyCompartment.reconfigure(readOnlyExt(isMutable)) });
  }, [isMutable]);

  const onPick = (e) => {
    const files = Array.from(e.target.files ?? []);
    e.target.value = '';
    if (files.length && viewRef.current) insertFiles(viewRef.current, files);
  };

  return (
    <>
      <div ref={hostRef} className={`${styles.host} ${isMutable ? '' : styles.readonly}`}>
        {isDragOver && (
          <div className={styles.dragOverlay}><span className={styles.dragOverlayText}>Drop to attach</span></div>
        )}
        {isMutable && (
          <>
            <input ref={fileInputRef} type="file" multiple accept={ACCEPT} style={{ display: 'none' }}
              data-testid="cm-file-input" onChange={onPick} />
            <button className={styles.uploadBtn} title="Attach file" onClick={() => fileInputRef.current?.click()}>
              📎 Attach
            </button>
          </>
        )}
      </div>
      {zoom && <ImageLightbox src={zoom.src} alt={zoom.alt} onClose={() => setZoom(null)} />}
    </>
  );
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

  // Wrapper hosts the editor plus the 📎 / drag overlay UI.
  return (
    <div className={styles.wrapper}>
      <EditorErrorBoundary>
        <CmEditorInner currentNotePath={currentNotePath} editorResetKey={editorResetKey} />
      </EditorErrorBoundary>
    </div>
  );
}
