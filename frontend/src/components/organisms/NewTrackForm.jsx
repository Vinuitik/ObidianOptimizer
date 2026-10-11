import { useState } from 'react';
import useStore from '../../store/useStore';
import { captureResource } from '../../api/capture';
import { classifyInput } from './TrackAddBox';
import styles from './NewTrackForm.module.css';

// One-step create: title (required) + optional pasted links/items + optional deadline.
// Creates the track, then adds the pasted content to it. Default type is 'book'
// (changeable later under Advanced in the track detail).
export default function NewTrackForm({ onCreated }) {
  const createTrack = useStore(s => s.createTrack);
  const updateTrack = useStore(s => s.updateTrack);
  const addTrackItem = useStore(s => s.addTrackItem);
  const fetchTrackItems = useStore(s => s.fetchTrackItems);
  const showToast = useStore(s => s.showToast);

  const [title, setTitle] = useState('');
  const [paste, setPaste] = useState('');
  const [deadline, setDeadline] = useState('');
  const [busy, setBusy] = useState(false);

  async function onSubmit(e) {
    e.preventDefault();
    if (!title.trim() || busy) return;
    setBusy(true);
    let track;
    try {
      track = await createTrack(title.trim(), 'book');
    } catch (err) {
      showToast(`Couldn't create track: ${err.message ?? err}`);
      setBusy(false);
      return;
    }
    const failures = [];
    try {
      if (deadline) await updateTrack(track.id, { deadline });
    } catch (err) { failures.push('deadline'); }

    const cls = classifyInput(paste);
    if (cls.kind === 'urls') {
      for (const url of cls.urls) {
        const res = await captureResource(url, track.id);
        if (!res.ok) failures.push(url);
      }
    } else if (cls.kind === 'text') {
      const lines = paste.split('\n').map(l => l.trim()).filter(Boolean);
      for (const line of lines) {
        try { await addTrackItem(track.id, line, null); } catch (err) { failures.push(line); }
      }
    }
    if (cls.kind !== 'empty') fetchTrackItems(track.id);
    if (failures.length) showToast(`Track created, but couldn't add: ${failures.join(', ')}`);
    setTitle(''); setPaste(''); setDeadline('');
    setBusy(false);
    onCreated?.(track);
  }

  return (
    <form className={styles.form} onSubmit={onSubmit} data-testid="new-track-form">
      <input
        className={styles.input}
        placeholder="New track title…"
        aria-label="Track title"
        value={title}
        onChange={e => setTitle(e.target.value)}
      />
      <textarea
        className={styles.input}
        rows={2}
        placeholder="Optional: paste links (one per line) or items"
        aria-label="Links or items"
        value={paste}
        onChange={e => setPaste(e.target.value)}
      />
      <label className={styles.deadline}>
        Deadline (optional)
        <input type="date" className={styles.input} value={deadline} onChange={e => setDeadline(e.target.value)} />
      </label>
      <button className={styles.primary} type="submit" disabled={busy || !title.trim()}>
        + New track
      </button>
    </form>
  );
}
