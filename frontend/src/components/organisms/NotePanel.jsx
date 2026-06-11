import { useState } from 'react';
import { createNote, fetchNoteContent, patchNote } from '../../api/notes';
import { computeHunks } from '../../utils/diff';
import FolderDropdown from '../molecules/FolderDropdown';
import styles from './NotePanel.module.css';

export default function NotePanel() {
  const [folder,   setFolder]   = useState('');
  const [name,     setName]     = useState('');
  const [notePath, setNotePath] = useState(null);
  const [raw,      setRaw]      = useState('');
  const [draft,    setDraft]    = useState('');
  const [editing,  setEditing]  = useState(false);
  const [saving,   setSaving]   = useState(false);
  const [creating, setCreating] = useState(false);
  const [error,    setError]    = useState(null);

  async function handleCreate() {
    if (!folder || !name.trim()) return;
    setCreating(true);
    setError(null);
    try {
      const { path } = await createNote(folder, name.trim());
      const content  = await fetchNoteContent(path);
      setNotePath(path);
      setRaw(content);
      setDraft(content);
    } catch (e) {
      setError(e.message ?? 'Failed to create note.');
    } finally {
      setCreating(false);
    }
  }

  async function handleSave() {
    if (!notePath) return;
    setSaving(true);
    setError(null);
    try {
      const hunks = computeHunks(raw, draft);
      if (hunks.length > 0) await patchNote(notePath, hunks);
      setRaw(draft);
      setEditing(false);
    } catch (e) {
      setError(e.message ?? 'Failed to save note.');
    } finally {
      setSaving(false);
    }
  }

  const noteBasename = notePath?.split(/[/\\]/).pop().replace(/\.md$/, '') ?? '';

  if (!notePath) {
    return (
      <div className={styles.panel}>
        <div className={styles.header}>
          <span className={styles.headerTitle}>New note</span>
        </div>
        <div className={styles.createForm}>
          <label className={styles.label}>Save to</label>
          <FolderDropdown value={folder} onChange={setFolder} />

          <label className={styles.label}>Note name</label>
          <input
            className={styles.input}
            placeholder="Untitled"
            value={name}
            onChange={e => setName(e.target.value)}
            onKeyDown={e => e.key === 'Enter' && handleCreate()}
          />

          {error && <span className={styles.error}>{error}</span>}

          <button
            className={styles.primaryBtn}
            disabled={!folder || !name.trim() || creating}
            onClick={handleCreate}
          >
            {creating ? 'Creating…' : 'Create note'}
          </button>
        </div>
      </div>
    );
  }

  return (
    <div className={styles.panel}>
      <div className={styles.header}>
        <span className={styles.headerTitle}>{noteBasename}</span>
        <div className={styles.headerActions}>
          {editing ? (
            <>
              <button className={styles.primaryBtn} disabled={saving} onClick={handleSave}>
                {saving ? 'Saving…' : 'Save'}
              </button>
              <button className={styles.ghostBtn} onClick={() => { setDraft(raw); setEditing(false); }}>
                Cancel
              </button>
            </>
          ) : (
            <button className={styles.ghostBtn} onClick={() => setEditing(true)}>Edit</button>
          )}
        </div>
      </div>
      {error && <span className={styles.error}>{error}</span>}
      <textarea
        className={styles.editor}
        value={draft}
        readOnly={!editing}
        onChange={e => setDraft(e.target.value)}
        spellCheck={false}
      />
    </div>
  );
}
