import { useCallback } from 'react';
import useStore from '../../store/useStore';
import MarkdownContent from '../molecules/MarkdownContent';
import styles from './NoteViewer.module.css';

// The reader surface for the currently-open note. Rendering now lives in the shared
// MarkdownContent molecule; NoteViewer only owns the store wiring: the cached HTML and
// resolving a clicked [[wikilink]] against the note index to navigate.
export default function NoteViewer() {
  const html = useStore(s => s.currentNoteHtml);
  const noteIndex = useStore(s => s.noteIndex);
  const openNote = useStore(s => s.openNote);

  const onOpenNote = useCallback((target) => {
    const basename = target.split(/[/\\]/).pop().toLowerCase();
    const fullPath = noteIndex.get(target.toLowerCase()) ?? noteIndex.get(basename);
    if (fullPath) openNote(fullPath);
  }, [noteIndex, openNote]);

  return <MarkdownContent html={html} onOpenNote={onOpenNote} className={styles.viewer} />;
}
