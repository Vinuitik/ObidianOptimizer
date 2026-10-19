import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import useStore from '../store/useStore';
import Button from '../components/atoms/Button';
import FolderTree from '../components/organisms/FolderTree';
import MarkdownView from '../components/molecules/MarkdownView';
import styles from './MobilePages.module.css';

// Notes tab (page title comes from TopBar): full-width FolderTree → tap a note → full-screen read-only render.
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

  const openTab   = useStore(s => s.openTab);
  const showToast = useStore(s => s.showToast);

  // Tap → open the reader explicitly. openTab is a no-op when the note is already
  // the active tab (currentNotePath unchanged), so we can't rely on it to flip the view.
  async function handleOpen(path) {
    if (path === useStore.getState().currentNotePath) { setViewing(true); return; }
    try {
      await openTab(path);
      setViewing(true);
    } catch {
      showToast({ message: "Couldn't open note (offline or slow connection)", tone: 'error' });
    }
  }

  function handleWikiOpen(target) {
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
          <Button variant="ghost" size="sm" onClick={() => setViewing(false)}>← Notes</Button>
          <span className={styles.readerTitle}>{title}</span>
        </header>
        <article className={styles.readerBody}>
          <MarkdownView content={currentNoteRaw || ''} onOpenNote={handleWikiOpen} />
        </article>
      </div>
    );
  }

  return (
    <div className={styles.page}>
      <Link to="/search" className={styles.searchLink}>Search notes</Link>
      <div className={styles.tree}>
        <FolderTree onOpenNote={handleOpen} />
      </div>
    </div>
  );
}
