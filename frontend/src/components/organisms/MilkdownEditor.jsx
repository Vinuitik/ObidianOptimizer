import { useCallback, useEffect, useLayoutEffect, useRef } from 'react';
import { Milkdown, MilkdownProvider, useEditor, useInstance } from '@milkdown/react';
import { commonmark } from '@milkdown/preset-commonmark';
import { gfm } from '@milkdown/preset-gfm';
import { history } from '@milkdown/plugin-history';
import { listener, listenerCtx } from '@milkdown/plugin-listener';
import { defaultValueCtx, Editor, rootCtx, editorViewOptionsCtx, editorViewCtx } from '@milkdown/core';

import useStore from '../../store/useStore';
import { splitFrontmatter } from '../../utils/frontmatter';
import { wikiLinkPlugin } from '../../utils/wikiLinkPlugin';
import {
  obsidianImagePlugin,
  obsidianImageNode$,
  setPendingBlobs,
  addPendingBlob,
  isWhitelisted,
  generateFilename,
} from '../../utils/obsidianImagePlugin';
import { hashtagPlugin } from '../../utils/hashtagPlugin';
import { livePreviewPlugin } from '../../utils/livePreviewPlugin';
import { cleanMilkdownOutput } from '../../utils/markdownCleanup';
import { mathPlugin } from '../../utils/mathPlugin';
import FrontmatterTable from '../molecules/FrontmatterTable';
import EditorErrorBoundary from './EditorErrorBoundary';
import styles from './MilkdownEditor.module.css';

// ── Inner editor: mounts once per note (key forces remount on note change) ───

function MilkdownEditorInner({ body, isMutable, onBodyChange, onFilePaste, onUnsupported }) {
  const [loading, getInstance] = useInstance();
  const skipFirst = useRef(true);

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

  // Paste handler — intercepts file pastes and routes them through the upload flow
  useEffect(() => {
    if (loading) return;
    const view = getInstance()?.action(ctx => ctx.get(editorViewCtx));
    if (!view) return;

    function handlePaste(e) {
      const files = Array.from(e.clipboardData?.files ?? []);
      if (!files.length) return;

      const accepted = files.filter(isWhitelisted);
      const rejected = files.filter(f => !isWhitelisted(f));

      // Always preventDefault when there are files so ProseMirror doesn't try to paste them
      e.preventDefault();

      if (rejected.length > 0) onUnsupported(rejected.length);
      if (!accepted.length) return;

      for (const file of accepted) {
        const filename = generateFilename(file);
        const blobURL = URL.createObjectURL(file);

        // 1. Update the module-level registry immediately so toDOM uses the blob URL
        addPendingBlob(filename, blobURL);

        // 2. Persist in the store (for tab switching and save flow)
        onFilePaste(filename, file, blobURL);

        // 3. Insert the node at the current cursor position
        getInstance()?.action(ctx => {
          const v = ctx.get(editorViewCtx);
          if (!v) return;
          const node = obsidianImageNode$.type(ctx).create({ filename });
          v.dispatch(v.state.tr.replaceSelectionWith(node));
        });
      }
    }

    view.dom.addEventListener('paste', handlePaste);
    return () => view.dom.removeEventListener('paste', handlePaste);
  }, [loading, onFilePaste, onUnsupported]);

  return <Milkdown />;
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
    const anchor = e.target.closest('[data-wiki-link]');
    if (!anchor) return;
    e.preventDefault();
    const target = anchor.getAttribute('data-wiki-link');
    const basename = target.split(/[/\\]/).pop().toLowerCase();
    const fullPath = noteIndex.get(target.toLowerCase()) ?? noteIndex.get(basename);
    if (fullPath) openTab(fullPath);
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
