import useStore from '../../store/useStore';
import styles from './NoteViewer.module.css';

export default function NoteViewer() {
  const html = useStore(s => s.currentNoteHtml);

  return (
    <div
      className={`${styles.viewer} markdown-body`}
      dangerouslySetInnerHTML={{ __html: html }}
    />
  );
}
