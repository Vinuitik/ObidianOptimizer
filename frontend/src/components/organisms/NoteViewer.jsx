import { useCallback } from 'react';
import useStore from '../../store/useStore';
import styles from './NoteViewer.module.css';

export default function NoteViewer() {
  const html = useStore(s => s.currentNoteHtml);
  const noteIndex = useStore(s => s.noteIndex);
  const openNote = useStore(s => s.openNote);

  const handleClick = useCallback((e) => {
    const anchor = e.target.closest('[data-wiki-link]');
    if (!anchor) return;
    e.preventDefault();
    const target = anchor.getAttribute('data-wiki-link');
    // try full target first (e.g. "Books/LikeSwitcher"), then fall back to basename ("LikeSwitcher")
    const basename = target.split(/[/\\]/).pop().toLowerCase();
    const fullPath = noteIndex.get(target.toLowerCase()) ?? noteIndex.get(basename);
    if (fullPath) {
      openNote(fullPath);
    }
  }, [noteIndex, openNote]);

  return (
    <div
      className={`${styles.viewer} markdown-body`}
      dangerouslySetInnerHTML={{ __html: html }}
      onClick={handleClick}
    />
  );
}
