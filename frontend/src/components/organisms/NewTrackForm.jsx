import { useState } from 'react';
import useStore from '../../store/useStore';
import { captureResource } from '../../api/capture';
import { classifyInput } from './TrackAddBox';
import Input from '../atoms/Input';
import Textarea from '../atoms/Textarea';
import Button from '../atoms/Button';
import Field from '../molecules/Field';
import styles from './NewTrackForm.module.css';

// No deadline → the scheduler has nothing to pace against, so new items would never reach Today.
// Default: 1 item/day, every weekday (weekday 0=Mon..6=Sun). Editable later under Advanced.
export const DEFAULT_SCHEDULE = { 0: 1, 1: 1, 2: 1, 3: 1, 4: 1, 5: 1, 6: 1 };

// One-step create: title (required) + optional pasted links/items + optional deadline.
// Creates the track, then adds the pasted content to it. Default type is 'book'
// (changeable later under Advanced in the track detail). After create, onCreated(track) fires
// so the page can jump to Today.
export default function NewTrackForm({ onCreated, autoFocus = false }) {
  const createTrack = useStore(s => s.createTrack);
  const updateTrack = useStore(s => s.updateTrack);
  const addTrackItem = useStore(s => s.addTrackItem);
  const fetchTrackItems = useStore(s => s.fetchTrackItems);
  const updateSchedule = useStore(s => s.updateSchedule);
  const fetchTodayPlan = useStore(s => s.fetchTodayPlan);
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
      else await updateSchedule(track.id, DEFAULT_SCHEDULE);
    } catch (err) { failures.push(deadline ? 'deadline' : 'daily schedule'); }

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
    try { await fetchTodayPlan(); } catch { /* Today refetches on mount */ }
    if (failures.length) showToast(`Track created, but couldn't add: ${failures.join(', ')}`);
    setTitle(''); setPaste(''); setDeadline('');
    setBusy(false);
    onCreated?.(track);
  }

  return (
    <form className={styles.form} onSubmit={onSubmit} data-testid="new-track-form">
      <Field label="Track title">
        <Input
          autoFocus={autoFocus}
          placeholder="e.g. Rust book"
          value={title}
          onChange={e => setTitle(e.target.value)}
        />
      </Field>
      <Field label="Links or items" hint="Optional. One link or item per line.">
        <Textarea rows={3} value={paste} onChange={e => setPaste(e.target.value)} />
      </Field>
      <Field label="Deadline (optional)" hint="No deadline? You'll get 1 item a day.">
        <Input type="date" value={deadline} onChange={e => setDeadline(e.target.value)} />
      </Field>
      <Button type="submit" variant="primary" loading={busy} disabled={!title.trim()}>
        + New track
      </Button>
    </form>
  );
}
