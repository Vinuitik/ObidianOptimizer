import useStore from '../../store/useStore';
import Button from '../atoms/Button';
import styles from './NewNoteForm.module.css';

export default function NewNoteForm() {
  const newNoteFolder = useStore(s => s.newNoteFolder);
  const newNoteName = useStore(s => s.newNoteName);
  const createNote = useStore(s => s.createNote);
  const cancelNewNote = useStore(s => s.cancelNewNote);

  const folderDisplay = newNoteFolder?.split(/[/\\]/).pop() ?? 'vault root';

  return (
    <div className={styles.form}>
      <span className={styles.folderHint}>Creating in: {folderDisplay}</span>
      <div className={styles.actions}>
        <Button onClick={() => newNoteName.trim() && createNote(newNoteFolder, newNoteName.trim())}>
          Create
        </Button>
        <Button variant="ghost" onClick={cancelNewNote}>Cancel</Button>
      </div>
    </div>
  );
}
