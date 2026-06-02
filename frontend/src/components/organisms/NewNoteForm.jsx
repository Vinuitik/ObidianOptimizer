import { useState } from 'react';
import useStore from '../../store/useStore';
import Button from '../atoms/Button';
import styles from './NewNoteForm.module.css';

export default function NewNoteForm() {
  const newNoteFolder = useStore(s => s.newNoteFolder);
  const createNote = useStore(s => s.createNote);
  const cancelNewNote = useStore(s => s.cancelNewNote);
  const [name, setName] = useState('');

  const handleCreate = async () => {
    const trimmed = name.trim();
    if (!trimmed) return;
    await createNote(newNoteFolder, trimmed);
  };

  const folderDisplay = newNoteFolder?.split(/[/\\]/).pop() ?? 'vault root';

  return (
    <div className={styles.form}>
      <span className={styles.folderHint}>Creating in: {folderDisplay}</span>
      <input
        className={styles.titleInput}
        placeholder="Note name"
        value={name}
        onChange={e => setName(e.target.value)}
        onKeyDown={e => { if (e.key === 'Enter') handleCreate(); if (e.key === 'Escape') cancelNewNote(); }}
        autoFocus
      />
      <div className={styles.actions}>
        <Button onClick={handleCreate}>Create</Button>
        <Button variant="ghost" onClick={cancelNewNote}>Cancel</Button>
      </div>
    </div>
  );
}
