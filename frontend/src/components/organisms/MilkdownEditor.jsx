import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { Milkdown, MilkdownProvider, useEditor, useInstance } from '@milkdown/react';
import { commonmark } from '@milkdown/preset-commonmark';
import { gfm } from '@milkdown/preset-gfm';
import { history } from '@milkdown/plugin-history';
import { listener, listenerCtx } from '@milkdown/plugin-listener';
import { prism } from '@milkdown/plugin-prism';
import 'prismjs/themes/prism-tomorrow.css';
import { defaultValueCtx, Editor, rootCtx, editorViewOptionsCtx, editorViewCtx } from '@milkdown/core';

import useStore from '../../store/useStore';
import { splitFrontmatter } from '../../utils/frontmatter';
import { wikiLinkPlugin, wikiLinkNode$ } from '../../utils/wikiLinkPlugin';
import { useSearch } from '../../utils/useSearch';
import {
  obsidianImagePlugin,
  obsidianImageNode$,
  setPendingBlobs,
  addPendingBlob,
  isWhitelisted,
  generateFilename,
  WHITELISTED_EXTS,
} from '../../utils/obsidianImagePlugin';

const ACCEPT = [...WHITELISTED_EXTS].join(',');
import { hashtagPlugin } from '../../utils/hashtagPlugin';
import { livePreviewPlugin } from '../../utils/livePreviewPlugin';
import { cleanMilkdownOutput } from '../../utils/markdownCleanup';
import { mathPlugin } from '../../utils/mathPlugin';
import FrontmatterTable from '../molecules/FrontmatterTable';
import EditorErrorBoundary from './EditorErrorBoundary';
import styles from './MilkdownEditor.module.css';

// ── Wiki-link suggestion dropdown (portal) ────────────────────────────────────

function WikiLinkSuggest({ query, rect, onSelect, onClose }) {
  const { results, loading } = useSearch(query, 1);

  useEffect(() => {
    function handleKey(e) { if (e.key === 'Escape') onClose(); }
    window.addEventListener('keydown', handleKey);
    return () => window.removeEventListener('keydown', handleKey);
  }, [onClose]);

  if (!loading && results.length === 0) return null;

  const style = { position: 'fixed', top: rect.bottom + 6, left: rect.left, zIndex: 9999 };

  return createPortal(
    <div className={styles.wikiSuggest} style={style}>
      {loading && results.length === 0 && (
        <div className={styles.wikiSuggestLoading}>Searching…</div>
      )}
      {results.map(r => {
        const title = r.notePath.split(/[/\\]/).pop().replace(/\.md$/, '');
        return (
          <button
            key={r.notePath}
            className={styles.wikiSuggestItem}
            onMouseDown={e => {
              e.preventDefault(); // keep editor focused
              onSelect(title, r.notePath);
            }}
          >
            <span className={styles.wikiSuggestTitle}>{title}</span>
            <span className={styles.wikiSuggestSnippet}>{r.snippet}</span>
          </button>
        );
      })}
    </div>,
    document.body
  );
}

// ── Inner editor: mounts once per note (key forces remount on note change) ───

function MilkdownEditorInner({ body, isMutable, onBodyChange, onFilePaste, onUnsupported }) {
  const [loading, getInstance] = useInstance();
  const skipFirst = useRef(true);

  // Wiki-link suggestion state
  const [wikiQuery, setWikiQuery] = useState(null); // null = closed
  const [wikiRect,  setWikiRect]  = useState(null);

  // Drag-over visual feedback
  const [isDragOver, setIsDragOver] = useState(false);

  // File picker ref
  const fileInputRef = useRef(null);

  useEditor((root) => {
    return Editor.make()
      .config((ctx) => {
        ctx.set(rootCtx, root);
        ctx.set(defaultValueCtx, body);
        ctx.update(editorViewOptionsCtx, prev => ({
          ...prev,
          editable: () => isMutable,
        }));
        ctx.get(listenerCtx).markdownUpdated((_, md) => {
          if (skipFirst.current) { skipFirst.current = false; return; }
          onBodyChange(cleanMilkdownOutput(md));
        });
      })
      .use(commonmark)
      .use(gfm)
      .use(history)
      .use(listener)
      .use(prism)
      .use(mathPlugin)
      .use(obsidianImagePlugin)   // before wikiLinkPlugin: ![[]] must be consumed first
      .use(wikiLinkPlugin)
      .use(hashtagPlugin)         // after wikiLink: [[#heading]] already consumed
      .use(livePreviewPlugin);
  });

  // Toggle editable after mount without remounting
  useEffect(() => {
    if (loading) return;
    getInstance()?.action((ctx) => {
      ctx.get(editorViewCtx)?.setProps({ editable: () => isMutable });
    });
  }, [isMutable, loading]);

  // File handling — paste, drag-and-drop, and file picker all share this logic
  useEffect(() => {
    if (loading) return;
    const view = getInstance()?.action(ctx => ctx.get(editorViewCtx));
    if (!view) return;

    function insertFiles(files) {
      const accepted = files.filter(isWhitelisted);
      const rejected = files.length - accepted.length;
      if (rejected > 0) onUnsupported(rejected);
      for (const file of accepted) {
        const filename = generateFilename(file);
        const blobURL  = URL.createObjectURL(file);
        addPendingBlob(filename, blobURL);
        onFilePaste(filename, file, blobURL);
        getInstance()?.action(ctx => {
          const v = ctx.get(editorViewCtx);
          if (!v) return;
          const node = obsidianImageNode$.type(ctx).create({ filename });
          v.dispatch(v.state.tr.replaceSelectionWith(node));
        });
      }
    }

    function htmlLinksToMarkdown(html) {
      const div = document.createElement('div');
      div.innerHTML = html;
      div.querySelectorAll('a[href]').forEach(a => {
        const href = a.getAttribute('href');
        const text = a.textContent.trim();
        if (href && text && !href.startsWith('#')) {
          a.replaceWith(`[${text}](${href})`);
        }
      });
      return div.textContent ?? '';
    }

    function handlePaste(e) {
      const files = Array.from(e.clipboardData?.files ?? []);
      if (files.length) {
        e.preventDefault();
        insertFiles(files);
        return;
      }

      // Convert HTML hyperlinks to markdown [text](url) before ProseMirror sees the paste
      const html = e.clipboardData?.getData('text/html');
      if (!html || !html.includes('<a ')) return;
      const converted = htmlLinksToMarkdown(html);
      if (!converted) return;
      e.preventDefault();
      getInstance()?.action(ctx => {
        const v = ctx.get(editorViewCtx);
        if (!v) return;
        const { state } = v;
        v.dispatch(state.tr.replaceSelectionWith(state.schema.text(converted), false));
      });
    }

    function handleDragOver(e) {
      const hasFiles = Array.from(e.dataTransfer?.items ?? []).some(i => i.kind === 'file');
      if (hasFiles) { e.preventDefault(); setIsDragOver(true); }
    }

    function handleDragLeave(e) {
      // only clear when leaving the editor entirely
      if (!view.dom.contains(e.relatedTarget)) setIsDragOver(false);
    }

    function handleDrop(e) {
      setIsDragOver(false);
      const files = Array.from(e.dataTransfer?.files ?? []);
      if (!files.length) return;
      e.preventDefault();
      insertFiles(files);
    }

    view.dom.addEventListener('paste',     handlePaste);
    view.dom.addEventListener('dragover',  handleDragOver);
    view.dom.addEventListener('dragleave', handleDragLeave);
    view.dom.addEventListener('drop',      handleDrop);
    return () => {
      view.dom.removeEventListener('paste',     handlePaste);
      view.dom.removeEventListener('dragover',  handleDragOver);
      view.dom.removeEventListener('dragleave', handleDragLeave);
      view.dom.removeEventListener('drop',      handleDrop);
    };
  }, [loading, onFilePaste, onUnsupported]);

  // Detect [[query pattern as the user types inside the editor
  useEffect(() => {
    if (loading || !isMutable) { setWikiQuery(null); return; }
    const view = getInstance()?.action(ctx => ctx.get(editorViewCtx));
    if (!view) return;

    function checkWikiQuery() {
      const sel = window.getSelection();
      if (!sel || !sel.focusNode) { setWikiQuery(null); return; }
      const node = sel.focusNode;
      // Only check text nodes inside the editor
      if (!view.dom.contains(node)) { setWikiQuery(null); return; }
      const textBefore = node.nodeType === Node.TEXT_NODE
        ? node.textContent.slice(0, sel.focusOffset)
        : '';
      const match = textBefore.match(/\[\[([^\]]{0,80})$/);
      if (match) {
        try {
          const range = sel.getRangeAt(0);
          const rect  = range.getBoundingClientRect();
          setWikiQuery(match[1]);
          setWikiRect(rect);
        } catch { setWikiQuery(null); }
      } else {
        setWikiQuery(null);
      }
    }

    view.dom.addEventListener('input', checkWikiQuery);
    document.addEventListener('selectionchange', checkWikiQuery);
    return () => {
      view.dom.removeEventListener('input', checkWikiQuery);
      document.removeEventListener('selectionchange', checkWikiQuery);
    };
  }, [loading, isMutable]);

  function insertWikiLink(displayName, notePath) {
    getInstance()?.action(ctx => {
      const view = ctx.get(editorViewCtx);
      if (!view) return;
      const { state } = view;
      const { $anchor } = state.selection;
      // Text content from start of current block to cursor
      const blockFrom = $anchor.start($anchor.depth);
      const cursorPos = $anchor.pos;
      const text = state.doc.textBetween(blockFrom, cursorPos);
      const idx = text.lastIndexOf('[[');
      if (idx === -1) return;
      const replaceFrom = blockFrom + idx;
      const wikiType = wikiLinkNode$.type(ctx);
      const wikiNode = wikiType.create({ target: displayName, display: null });
      view.dispatch(state.tr.replaceWith(replaceFrom, cursorPos, wikiNode));
      view.focus();
    });
    setWikiQuery(null);
  }

  function handleFileInputChange(e) {
    const files = Array.from(e.target.files ?? []);
    e.target.value = ''; // reset so the same file can be re-picked
    if (!files.length) return;
    // reuse the same insertion logic via a synthetic call
    const accepted = files.filter(isWhitelisted);
    const rejected = files.length - accepted.length;
    if (rejected > 0) onUnsupported(rejected);
    for (const file of accepted) {
      const filename = generateFilename(file);
      const blobURL  = URL.createObjectURL(file);
      addPendingBlob(filename, blobURL);
      onFilePaste(filename, file, blobURL);
      getInstance()?.action(ctx => {
        const v = ctx.get(editorViewCtx);
        if (!v) return;
        const node = obsidianImageNode$.type(ctx).create({ filename });
        v.dispatch(v.state.tr.replaceSelectionWith(node));
      });
    }
  }

  return (
    <>
      <Milkdown />
      {isDragOver && (
        <div className={styles.dragOverlay}>
          <span className={styles.dragOverlayText}>Drop to attach</span>
        </div>
      )}
      {isMutable && (
        <>
          <input
            ref={fileInputRef}
            type="file"
            multiple
            accept={ACCEPT}
            style={{ display: 'none' }}
            onChange={handleFileInputChange}
          />
          <button
            className={styles.uploadBtn}
            title="Attach file"
            onClick={() => fileInputRef.current?.click()}
          >
            📎 Attach
          </button>
        </>
      )}
      {wikiQuery !== null && wikiRect && (
        <WikiLinkSuggest
          query={wikiQuery}
          rect={wikiRect}
          onSelect={insertWikiLink}
          onClose={() => setWikiQuery(null)}
        />
      )}
    </>
  );
}

// ── Outer component: reads store, handles wiki-link click delegation ──────────

export default function MilkdownEditor() {
  const currentNotePath    = useStore(s => s.currentNotePath);
  const pendingFrontmatter = useStore(s => s.pendingFrontmatter);
  const pendingRaw         = useStore(s => s.pendingRaw);
  const isMutable          = useStore(s => s.isMutable);
  const editorResetKey     = useStore(s => s.editorResetKey);
  const updatePending      = useStore(s => s.updatePending);
  const noteIndex          = useStore(s => s.noteIndex);
  const openTab            = useStore(s => s.openTab);
  const pendingFiles       = useStore(s => s.pendingFiles);
  const addPendingFile     = useStore(s => s.addPendingFile);
  const showToast          = useStore(s => s.showToast);

  // Keep the module-level blob registry in sync with store (handles tab switches)
  useLayoutEffect(() => {
    setPendingBlobs(pendingFiles);
  }, [pendingFiles]);

  const handleFilePaste = useCallback((filename, file, blobURL) => {
    addPendingFile(filename, file, blobURL);
  }, [addPendingFile]);

  const handleUnsupported = useCallback((count) => {
    showToast(`${count} file${count > 1 ? 's' : ''} skipped — accepted types: images, video, audio, PDF.`);
  }, [showToast]);

  const handleClick = useCallback((e) => {
    if (isMutable) return;

    const wikiAnchor = e.target.closest('[data-wiki-link]');
    if (wikiAnchor) {
      e.preventDefault();
      const target = wikiAnchor.getAttribute('data-wiki-link');
      const basename = target.split(/[/\\]/).pop().toLowerCase();
      const fullPath = noteIndex.get(target.toLowerCase()) ?? noteIndex.get(basename);
      if (fullPath) openTab(fullPath);
      return;
    }

    const link = e.target.closest('a[href]');
    if (link) {
      const href = link.getAttribute('href');
      if (href && !href.startsWith('#')) {
        e.preventDefault();
        window.open(href, '_blank', 'noopener,noreferrer');
      }
    }
  }, [isMutable, noteIndex, openTab]);

  if (!currentNotePath) {
    return (
      <div className={styles.empty}>
        <span className={styles.emptyIcon}>📄</span>
        <span className={styles.emptyText}>Select a note to get started</span>
      </div>
    );
  }

  const { body } = splitFrontmatter(pendingRaw);

  return (
    <div className={styles.wrapper} onClick={handleClick}>
      <FrontmatterTable frontmatter={pendingFrontmatter} />
      <div className={styles.milkdownWrapper}>
        <EditorErrorBoundary>
          <MilkdownProvider key={`${currentNotePath}-${editorResetKey}`}>
            <MilkdownEditorInner
              body={body}
              isMutable={isMutable}
              onBodyChange={updatePending}
              onFilePaste={handleFilePaste}
              onUnsupported={handleUnsupported}
            />
          </MilkdownProvider>
        </EditorErrorBoundary>
      </div>
    </div>
  );
}
