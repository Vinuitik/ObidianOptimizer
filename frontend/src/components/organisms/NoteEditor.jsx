import { useState } from 'react';
import useStore from '../../store/useStore';
import Button from '../atoms/Button';
import styles from './NoteEditor.module.css';

function noteTitle(fullPath) {
  if (!fullPath) return '';
  return fullPath.split(/[/\\]/).pop().replace(/\.md$/, '');
}

export default function NoteEditor() {
  const currentNoteRaw = useStore(s => s.currentNoteRaw);
  const currentNotePath = useStore(s => s.currentNotePath);
  const saveNote = useStore(s => s.saveNote);
  const cancelEdit = useStore(s => s.cancelEdit);
  const deleteNote = useStore(s => s.deleteNote);

  const [title, setTitle] = useState(() => noteTitle(currentNotePath));
  const [content, setContent] = useState(currentNoteRaw);

  const handleDelete = async () => {
    if (!window.confirm(`Move "${title}" to trash?`)) return;
    await deleteNote(currentNotePath);
  };

  return (
    <div className={styles.editor}>
      <input
        className={styles.titleInput}
        value={title}
        onChange={e => setTitle(e.target.value)}
        placeholder="Note name"
      />
      <textarea
        className={styles.textarea}
        value={content}
        onChange={e => setContent(e.target.value)}
      />
      <div className={styles.actions}>
        <Button onClick={() => saveNote(title, content)}>Save</Button>
        <Button variant="ghost" onClick={cancelEdit}>Cancel</Button>
        <span className={styles.spacer} />
        <Button variant="danger" onClick={handleDelete}>Move to trash</Button>
      </div>
    </div>
  );
}
