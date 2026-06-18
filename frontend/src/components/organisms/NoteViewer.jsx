import { useCallback } from 'react';
import useStore from '../../store/useStore';
import styles from './NoteViewer.module.css';

export default function NoteViewer() {
  const html = useStore(s => s.currentNoteHtml);
  const noteIndex = useStore(s => s.noteIndex);
  const openNote = useStore(s => s.openNote);

  const handleClick = useCallback((e) => {
    const wikiAnchor = e.target.closest('[data-wiki-link]');
    if (wikiAnchor) {
      e.preventDefault();
      const target = wikiAnchor.getAttribute('data-wiki-link');
      const basename = target.split(/[/\\]/).pop().toLowerCase();
      const fullPath = noteIndex.get(target.toLowerCase()) ?? noteIndex.get(basename);
      if (fullPath) openNote(fullPath);
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
  }, [noteIndex, openNote]);

  return (
    <div
      className={`${styles.viewer} markdown-body`}
      dangerouslySetInnerHTML={{ __html: html }}
      onClick={handleClick}
    />
  );
}
