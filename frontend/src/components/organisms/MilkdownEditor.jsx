import { useCallback, useEffect, useRef } from 'react';
import { Milkdown, MilkdownProvider, useEditor, useInstance } from '@milkdown/react';
import { commonmark } from '@milkdown/preset-commonmark';
import { gfm } from '@milkdown/preset-gfm';
import { history } from '@milkdown/plugin-history';
import { listener, listenerCtx } from '@milkdown/plugin-listener';
import { defaultValueCtx, Editor, rootCtx, editorViewOptionsCtx, editorViewCtx } from '@milkdown/core';

import useStore from '../../store/useStore';
import { splitFrontmatter } from '../../utils/frontmatter';
import { wikiLinkPlugin } from '../../utils/wikiLinkPlugin';
import { obsidianImagePlugin } from '../../utils/obsidianImagePlugin';
import { hashtagPlugin } from '../../utils/hashtagPlugin';
import { livePreviewPlugin } from '../../utils/livePreviewPlugin';
import { cleanMilkdownOutput } from '../../utils/markdownCleanup';
import { mathPlugin } from '../../utils/mathPlugin';
import FrontmatterTable from '../molecules/FrontmatterTable';
import EditorErrorBoundary from './EditorErrorBoundary';
import styles from './MilkdownEditor.module.css';

// ── Inner editor: mounts once per note (key forces remount on note change) ───

function MilkdownEditorInner({ body, isMutable, onBodyChange }) {
  const [loading, getInstance] = useInstance();
  const skipFirst = useRef(true);
  const renderCount = useRef(0);
  renderCount.current += 1;

  console.debug(`[MilkdownEditor] render #${renderCount.current} — body length: ${body.length} | first 80: ${JSON.stringify(body.slice(0, 80))}`);

  useEffect(() => {
    console.debug(`[MilkdownEditor] MOUNTED (render #${renderCount.current}) — body length: ${body.length}`);
    return () => console.debug('[MilkdownEditor] UNMOUNTED');
  }, []);

  useEditor((root) => {
    console.debug('[MilkdownEditor] useEditor factory called — body length:', body.length,
      '| first 80:', JSON.stringify(body.slice(0, 80)));
    return Editor.make()
      .config((ctx) => {
        console.debug('[MilkdownEditor] config callback — setting defaultValueCtx, body length:', body.length);
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
      .use(obsidianImagePlugin)
      .use(wikiLinkPlugin)
      .use(hashtagPlugin)
      .use(livePreviewPlugin);
  });

  useEffect(() => {
    if (loading) {
      console.debug('[MilkdownEditor] loading=true (editor not ready yet)');
      return;
    }
    const instance = getInstance();
    const docContent = instance?.action(ctx => {
      try { return ctx.get(editorViewCtx)?.state.doc.textContent.slice(0, 120); }
      catch { return '<error reading doc>'; }
    });
    console.debug('[MilkdownEditor] editor READY — isMutable:', isMutable,
      '| doc.textContent (first 120):', JSON.stringify(docContent));
    instance?.action((ctx) => {
      ctx.get(editorViewCtx)?.setProps({ editable: () => isMutable });
    });
  }, [isMutable, loading]);

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

  console.debug('[MilkdownEditor] rendering note:', currentNotePath,
    '| pendingRaw length:', pendingRaw.length, '| body length:', body.length);

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
            />
          </MilkdownProvider>
        </EditorErrorBoundary>
      </div>
    </div>
  );
}
