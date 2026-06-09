import { useCallback, useEffect, useRef } from 'react';
import { Milkdown, MilkdownProvider, useEditor, useInstance } from '@milkdown/react';
import { commonmark } from '@milkdown/preset-commonmark';
import { history } from '@milkdown/plugin-history';
import { listener, listenerCtx } from '@milkdown/plugin-listener';
import { defaultValueCtx, Editor, rootCtx, editorViewOptionsCtx, editorViewCtx } from '@milkdown/core';

import useStore from '../../store/useStore';
import { splitFrontmatter } from '../../utils/frontmatter';
import { wikiLinkPlugin } from '../../utils/wikiLinkPlugin';
import { obsidianImagePlugin } from '../../utils/obsidianImagePlugin';
import { hashtagPlugin } from '../../utils/hashtagPlugin';
import { cleanMilkdownOutput } from '../../utils/markdownCleanup';
import FrontmatterTable from '../molecules/FrontmatterTable';
import styles from './MilkdownEditor.module.css';

// ── Inner editor: mounts once per note (key forces remount on note change) ───

function MilkdownEditorInner({ body, isMutable, onBodyChange }) {
  const [loading, getInstance] = useInstance();
  // Skip the first markdownUpdated fire — Milkdown serialises the initial body
  // on mount, which can differ from the raw file content (trailing newline, blank
  // lines around html nodes, etc.) and would cause spurious hunks on sync.
  const skipFirst = useRef(true);

  useEditor((root) =>
    Editor.make()
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
      .use(history)
      .use(listener)
      .use(obsidianImagePlugin)   // must run before wikiLinkPlugin (images contain [[]])
      .use(wikiLinkPlugin)
      .use(hashtagPlugin)         // after wikiLink so [[#heading]] is already consumed
  );

  // Toggle editable on the live ProseMirror view when isMutable changes
  useEffect(() => {
    if (loading) return;
    getInstance()?.action((ctx) => {
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
  const updatePending      = useStore(s => s.updatePending);
  const noteIndex          = useStore(s => s.noteIndex);
  const openNote           = useStore(s => s.openNote);

  const handleClick = useCallback((e) => {
    // Only navigate wiki links when NOT in edit mode (editing needs click for cursor)
    if (isMutable) return;
    const anchor = e.target.closest('[data-wiki-link]');
    if (!anchor) return;
    e.preventDefault();
    const target = anchor.getAttribute('data-wiki-link');
    const basename = target.split(/[/\\]/).pop().toLowerCase();
    const fullPath = noteIndex.get(target.toLowerCase()) ?? noteIndex.get(basename);
    if (fullPath) openNote(fullPath);
  }, [isMutable, noteIndex, openNote]);

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
        <MilkdownProvider>
          <MilkdownEditorInner
            key={currentNotePath}
            body={body}
            isMutable={isMutable}
            onBodyChange={updatePending}
          />
        </MilkdownProvider>
      </div>
    </div>
  );
}
