import { useEffect, useMemo, useState } from 'react';
import useStore from '../store/useStore';
import FolderTree from '../components/organisms/FolderTree';
import { renderMarkdown } from '../utils/markdown';
import styles from './MobilePages.module.css';

// Notes tab: full-width FolderTree → tap a note → full-screen read-only render.
// Mobile v1 is view-only (no Milkdown editing on a phone — deliberate scope cut),
// so we render currentNoteRaw with the shared renderMarkdown() instead of the
// desktop WYSIWYG editor. Wiki-links and ![[image]] embeds reuse the same markup.
export default function MobileNotesPage() {
  const isAuthenticated  = useStore(s => s.isAuthenticated);
  const fetchRootChildren = useStore(s => s.fetchRootChildren);
  const currentNoteRaw   = useStore(s => s.currentNoteRaw);
  const currentNotePath  = useStore(s => s.currentNotePath);
  const openNote         = useStore(s => s.openNote);
  const noteIndex        = useStore(s => s.noteIndex);

  const [viewing, setViewing] = useState(false);

  useEffect(() => {
    if (isAuthenticated) fetchRootChildren();
  }, [isAuthenticated, fetchRootChildren]);

  // Show the reader whenever a note becomes current (FolderTree calls openTab).
  useEffect(() => {
    if (currentNotePath) setViewing(true);
  }, [currentNotePath]);

  const html = useMemo(() => renderMarkdown(currentNoteRaw || ''), [currentNoteRaw]);

  function handleWikiClick(e) {
    const anchor = e.target.closest('[data-wiki-link]');
    if (!anchor) return;
    e.preventDefault();
    const target = anchor.getAttribute('data-wiki-link');
    const basename = target.split(/[/\\]/).pop().toLowerCase();
    const fullPath = noteIndex.get(target.toLowerCase()) ?? noteIndex.get(basename);
    if (fullPath) openNote(fullPath);
  }

  if (!isAuthenticated) {
    return <div className={styles.gate}>Sign in to browse your vault.</div>;
  }

  if (viewing && currentNotePath) {
    const title = currentNotePath.split(/[/\\]/).pop().replace(/\.md$/, '');
    return (
      <div className={styles.reader}>
        <header className={styles.readerBar}>
          <button className={styles.back} onClick={() => setViewing(false)}>← Notes</button>
          <span className={styles.readerTitle}>{title}</span>
        </header>
        <article
          className={`${styles.readerBody} markdown-body`}
          dangerouslySetInnerHTML={{ __html: html }}
          onClick={handleWikiClick}
        />
      </div>
    );
  }

  return (
    <div className={styles.page}>
      <h1 className={styles.pageTitle}>Notes</h1>
      <div className={styles.tree}>
        <FolderTree />
      </div>
    </div>
  );
}
