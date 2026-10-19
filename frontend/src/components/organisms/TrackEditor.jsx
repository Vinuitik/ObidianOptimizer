import { useEffect, useRef, useState } from 'react';
import { AnimatePresence, motion, useReducedMotion } from 'framer-motion';
import useStore from '../../store/useStore';
import Button from '../atoms/Button';
import Badge from '../atoms/Badge';
import Input from '../atoms/Input';
import Textarea from '../atoms/Textarea';
import Select from '../atoms/Select';
import Checkbox from '../atoms/Checkbox';
import Spinner from '../atoms/Spinner';
import Field from '../molecules/Field';
import Card from '../molecules/Card';
import Disclosure from '../molecules/Disclosure';
import { listItem } from '../motion/presets';
import { generateMinicourse, fetchMinicourseJob, approveMinicourse, importCsv, commitImport } from '../../api/tracks';
import TrackAddBox, { ItemTypeChip, itemKind } from './TrackAddBox';
import styles from './TracksPlan.module.css';

// Track editor pieces (moved from the old TracksPage). Rendered inside the Week drawer.
function cap(s) { return s ? s[0].toUpperCase() + s.slice(1) : s; }
export const WEEKDAYS = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];
const TRACK_TYPES = ['book', 'course', 'article', 'custom', 'subscription'];
const TYPE_OPTIONS = TRACK_TYPES.map(t => ({ value: t, label: cap(t) }));
const STATUS_OPTIONS = ['active', 'paused', 'done', 'archived'].map(t => ({ value: t, label: cap(t) }));
const PRIORITY_OPTIONS = ['must', 'should', 'could'].map(t => ({ value: t, label: cap(t) }));

const listItemReduced = { initial: { opacity: 0 }, animate: { opacity: 1 }, exit: { opacity: 0 } };

// singleton groups (groupId set but no siblings) render flat like ungrouped items.
function groupForDisplay(items) {
  const byGroup = {};
  items.forEach(i => { if (i.groupId) (byGroup[i.groupId] ??= []).push(i); });
  const rendered = new Set();
  const rows = [];
  for (const item of items) {
    if (item.groupId && byGroup[item.groupId].length > 1) {
      if (rendered.has(item.groupId)) continue;
      rendered.add(item.groupId);
      rows.push({ type: 'group', groupId: item.groupId, members: byGroup[item.groupId] });
    } else {
      rows.push({ type: 'item', item });
    }
  }
  return rows;
}

// ── Import from spreadsheet (Phase 3) ───────────────────────────────────────

export function ImportPanel({ onClose }) {
  const fetchTracks = useStore(s => s.fetchTracks);
  const showToast   = useStore(s => s.showToast);

  const [text, setText] = useState('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);
  const [previewTracks, setPreviewTracks] = useState(null); // [{ title, type }]
  const [previewItems, setPreviewItems]   = useState(null); // [{ _key, trackIndex, title, status }]
  const [committing, setCommitting] = useState(false);
  const [result, setResult] = useState(null); // { tracksCreated, itemsCreated }

  async function handleFile(e) {
    const file = e.target.files?.[0];
    if (!file) return;
    setText(await file.text());
  }

  async function handlePreview() {
    if (!text.trim()) return;
    setLoading(true);
    setError(null);
    try {
      const res = await importCsv(text);
      setPreviewTracks(res.tracks.map(t => ({ ...t })));
      setPreviewItems(res.items.map((it, i) => ({ ...it, _key: i })));
    } catch (e) {
      setError(e.message ?? String(e));
    } finally {
      setLoading(false);
    }
  }

  function editTrack(i, patch) {
    setPreviewTracks(ts => ts.map((t, idx) => idx === i ? { ...t, ...patch } : t));
  }

  function deleteTrack(i) {
    setPreviewTracks(ts => ts.filter((_, idx) => idx !== i));
    setPreviewItems(its => its
      .filter(it => it.trackIndex !== i)
      .map(it => it.trackIndex > i ? { ...it, trackIndex: it.trackIndex - 1 } : it));
  }

  function editItem(key, patch) {
    setPreviewItems(its => its.map(it => it._key === key ? { ...it, ...patch } : it));
  }

  function deleteItem(key) {
    setPreviewItems(its => its.filter(it => it._key !== key));
  }

  async function handleCommit() {
    setCommitting(true);
    try {
      const items = previewItems.map(({ _key, ...rest }) => rest);
      const res = await commitImport({ tracks: previewTracks, items });
      setResult(res);
      setPreviewTracks(null);
      setPreviewItems(null);
      fetchTracks();
    } catch (e) {
      showToast(`Couldn't import: ${e.message ?? e}`);
    } finally {
      setCommitting(false);
    }
  }

  if (result) {
    return (
      <div className={styles.stack}>
        <p>Imported {result.tracksCreated} track(s) and {result.itemsCreated} item(s).</p>
        <div className={styles.actionsRow}>
          <Button variant="primary" onClick={onClose}>Done</Button>
        </div>
      </div>
    );
  }

  if (previewTracks) {
    return (
      <div className={styles.stack}>
        <h3 className={styles.sectionTitle}>Import preview</h3>
        {previewTracks.map((t, i) => (
          <Card key={i} className={styles.stack}>
            <div className={styles.rowWrap}>
              <Input
                aria-label="Track title"
                className={styles.grow}
                value={t.title}
                onChange={e => editTrack(i, { title: e.target.value })}
              />
              <Select
                aria-label="Track type"
                options={TYPE_OPTIONS}
                value={t.type}
                onChange={e => editTrack(i, { type: e.target.value })}
              />
              <Button variant="danger" size="sm" onClick={() => deleteTrack(i)}>Delete track</Button>
            </div>
            <ul className={styles.itemList}>
              {previewItems.filter(it => it.trackIndex === i).map(it => (
                <li key={it._key} className={styles.itemRow}>
                  <Input
                    aria-label="Item title"
                    className={styles.grow}
                    value={it.title}
                    onChange={e => editItem(it._key, { title: e.target.value })}
                  />
                  <Badge>{cap(it.status)}</Badge>
                  <Button variant="ghost" size="sm" aria-label="Remove item" onClick={() => deleteItem(it._key)}>×</Button>
                </li>
              ))}
              {previewItems.filter(it => it.trackIndex === i).length === 0 && (
                <li className={styles.muted}>No items in this track.</li>
              )}
            </ul>
          </Card>
        ))}
        {previewTracks.length === 0 && <p className={styles.muted}>No tracks left to import.</p>}
        <div className={styles.actionsRow}>
          <Button variant="primary" onClick={handleCommit} loading={committing} disabled={previewTracks.length === 0}>
            Commit import
          </Button>
          <Button onClick={onClose}>Cancel</Button>
        </div>
      </div>
    );
  }

  return (
    <div className={styles.stack}>
      <h3 className={styles.sectionTitle}>Import from spreadsheet</h3>
      <p className={styles.muted}>
        Paste CSV text or select a .csv file. An LLM parses it into tracks and items for you to review before anything is created.
      </p>
      <Textarea
        aria-label="CSV text"
        placeholder="Paste CSV here…"
        value={text}
        onChange={e => setText(e.target.value)}
        rows={10}
      />
      <input type="file" accept=".csv,text/csv" aria-label="CSV file" onChange={handleFile} />
      {error && <p className={styles.error} role="alert">{error}</p>}
      <div className={styles.actionsRow}>
        <Button variant="primary" onClick={handlePreview} loading={loading} disabled={!text.trim()}>
          {loading ? 'Parsing…' : 'Preview'}
        </Button>
        <Button onClick={onClose}>Cancel</Button>
      </div>
    </div>
  );
}

// Daily capacity ceiling used by Phase 1c's Today allocation (Normal mode only) — plain
// per-weekday numbers, not a base×multiplier, so any single day can be hand-tuned without
// touching the others. Lives on the Tracks page (not generic Settings) since it's
// tracks-specific, same as the weekly schedule editor.
export function CapacityPanel() {
  const trackCapacity      = useStore(s => s.trackCapacity);
  const fetchTrackCapacity = useStore(s => s.fetchTrackCapacity);
  const saveTrackCapacity  = useStore(s => s.saveTrackCapacity);
  const showToast          = useStore(s => s.showToast);
  const [values, setValues] = useState({});

  useEffect(() => { fetchTrackCapacity(); }, [fetchTrackCapacity]);
  useEffect(() => { setValues(trackCapacity); }, [trackCapacity]);

  async function save(weekday, value) {
    const n = Number(value);
    if (!value || Number.isNaN(n) || n < 0) return;
    try {
      await saveTrackCapacity({ [weekday]: n });
    } catch (e) {
      showToast(`Couldn't save capacity: ${e.message ?? e}`);
    }
  }

  return (
    <div className={styles.capacityPanel}>
      <h3 className={styles.sectionTitle}>Daily capacity</h3>
      <p className={styles.muted}>Most items to show on Today per weekday.</p>
      <div className={styles.capacityRow}>
        {WEEKDAYS.map((label, i) => (
          <div key={i} className={styles.capacityCell}>
            <span className={styles.muted}>{label}</span>
            <Input
              type="number"
              min={0}
              aria-label={`${label} capacity`}
              value={values[i] ?? ''}
              onChange={e => setValues(v => ({ ...v, [i]: e.target.value }))}
              onBlur={e => save(i, e.target.value)}
            />
          </div>
        ))}
      </div>
    </div>
  );
}

export function TrackDetail({ track, onDeleted }) {
  const updateTrack   = useStore(s => s.updateTrack);
  const deleteTrack   = useStore(s => s.deleteTrack);
  const showToast     = useStore(s => s.showToast);

  const [title, setTitle] = useState(track.title);
  const [type, setType]   = useState(track.type);
  const [deadline, setDeadline] = useState(track.deadline ?? '');
  const [priority, setPriority] = useState(track.priority ?? '');
  const [includeInProgress, setIncludeInProgress] = useState(track.includeInProgress);

  // Reset local form state whenever a different track is selected.
  useEffect(() => {
    setTitle(track.title);
    setType(track.type);
    setDeadline(track.deadline ?? '');
    setPriority(track.priority ?? '');
    setIncludeInProgress(track.includeInProgress);
  }, [track.id]);

  async function saveField(patch) {
    try {
      await updateTrack(track.id, patch);
    } catch (e) {
      showToast(`Couldn't save: ${e.message ?? e}`);
    }
  }

  async function handleDelete() {
    if (!window.confirm(`Delete "${track.title}" and all its items? This can't be undone.`)) return;
    try {
      await deleteTrack(track.id);
      onDeleted();
    } catch (e) {
      showToast(`Couldn't delete: ${e.message ?? e}`);
    }
  }

  return (
    <div className={styles.stack}>
      <div className={styles.detailHeader}>
        <Input
          aria-label="Track name"
          className={styles.grow}
          value={title}
          onChange={e => setTitle(e.target.value)}
          onBlur={() => title.trim() && title !== track.title && saveField({ title: title.trim() })}
        />
        <Button variant="danger" size="sm" onClick={handleDelete}>Delete track</Button>
      </div>

      <Card>
        <Field label="Deadline">
          <Input
            type="date"
            value={deadline}
            onChange={e => setDeadline(e.target.value)}
            onBlur={() => {
              if (deadline) saveField({ deadline });
              else if (track.deadline) saveField({ clearDeadline: true });
            }}
          />
        </Field>
      </Card>
      <Card>
        <TrackItemsEditor trackId={track.id} />
      </Card>
      <Card>
        <Disclosure title="Advanced">
          <div className={styles.stack}>
            <Field label="Type">
              <Select
                options={TYPE_OPTIONS}
                value={type}
                onChange={e => { setType(e.target.value); saveField({ type: e.target.value }); }}
              />
            </Field>
            <Field label="Status">
              <Select
                options={STATUS_OPTIONS}
                value={track.status}
                onChange={e => saveField({ status: e.target.value })}
              />
            </Field>
            <Checkbox
              label="Show on Progress tab"
              checked={Boolean(includeInProgress)}
              onChange={e => { setIncludeInProgress(e.target.checked); saveField({ includeInProgress: e.target.checked }); }}
            />
            {deadline && (
              <Field label="Priority">
                <Select
                  options={PRIORITY_OPTIONS}
                  value={priority || 'should'}
                  onChange={e => { setPriority(e.target.value); saveField({ priority: e.target.value }); }}
                />
              </Field>
            )}
            <TrackScheduleEditor trackId={track.id} />
            <MinicoursePanel trackId={track.id} />
          </div>
        </Disclosure>
      </Card>
    </div>
  );
}

// ── Mini-course (Phase 2) ────────────────────────────────────────────────────

function MinicoursePanel({ trackId }) {
  const items = useStore(s => s.trackItems[trackId]) ?? [];
  const fetchTrackItems = useStore(s => s.fetchTrackItems);
  const showToast = useStore(s => s.showToast);
  const [job, setJob] = useState(null);
  const [checked, setChecked] = useState({}); // lesson index -> bool
  const [busy, setBusy] = useState(false);

  const hasNotes = items.some(i => i.notePath);

  // Different track selected — drop any job state from the previous one.
  useEffect(() => { setJob(null); }, [trackId]);

  const polling = job != null && (job.status === 'QUEUED' || job.status === 'RUNNING');
  useEffect(() => {
    if (!polling) return undefined;
    const id = setInterval(async () => {
      try {
        const updated = await fetchMinicourseJob(job.id);
        setJob(updated);
        if (updated.status === 'DONE') fetchTrackItems(trackId);
      } catch (e) {
        showToast(`Couldn't check mini-course status: ${e.message ?? e}`);
      }
    }, 2000);
    return () => clearInterval(id);
  }, [polling, job?.id, trackId, fetchTrackItems, showToast]);

  useEffect(() => {
    if (job?.status === 'AWAITING_APPROVAL' && job.plan) {
      setChecked(Object.fromEntries(job.plan.lessons.map((_, i) => [i, true])));
    }
  }, [job?.status, job?.plan]);

  async function start() {
    setBusy(true);
    try {
      setJob(await generateMinicourse(trackId));
    } catch (e) {
      showToast(`Couldn't start mini-course: ${e.message ?? e}`);
    } finally {
      setBusy(false);
    }
  }

  async function approve() {
    const approvedIndexes = Object.entries(checked).filter(([, v]) => v).map(([i]) => Number(i));
    setBusy(true);
    try {
      setJob(await approveMinicourse(job.id, approvedIndexes));
    } catch (e) {
      showToast(`Couldn't approve mini-course: ${e.message ?? e}`);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className={styles.stack}>
      <h3 className={styles.sectionTitle}>Generate a course</h3>

      {(!job || job.status === 'FAILED') && (
        <div className={styles.actionsRow}>
          <Button
            onClick={start}
            loading={busy}
            disabled={!hasNotes}
            title={hasNotes ? undefined : 'Add at least one item with a linked note first'}
          >
            Generate mini-course
          </Button>
        </div>
      )}

      {job && (job.status === 'QUEUED' || job.status === 'RUNNING') && (
        <div className={styles.statusRow} aria-live="polite">
          <Spinner size={14} label="Working" />
          <span>{job.stage === 'lessons' ? 'Writing lessons…' : 'Generating syllabus…'}</span>
        </div>
      )}

      {job?.status === 'AWAITING_APPROVAL' && job.plan && (
        <div className={styles.stack}>
          <p className={styles.planTitle}>{job.plan.course_title}</p>
          <ul className={styles.itemList}>
            {job.plan.lessons.map((lesson, i) => (
              <li key={i} className={styles.lessonRow}>
                <Checkbox
                  checked={Boolean(checked[i])}
                  onChange={e => setChecked(c => ({ ...c, [i]: e.target.checked }))}
                  label={
                    <span className={styles.lessonText}>
                      <span>{lesson.title}</span>
                      <span className={styles.muted}>{lesson.objective}</span>
                    </span>
                  }
                />
              </li>
            ))}
          </ul>
          <div className={styles.actionsRow}>
            <Button variant="primary" onClick={approve} loading={busy}>Approve &amp; write notes</Button>
          </div>
        </div>
      )}

      {job?.status === 'DONE' && (
        <div className={styles.stack}>
          <p>{job.results?.length ?? 0} lesson note(s) written.</p>
          {job.lesson_failures?.length > 0 && (
            <p className={styles.error}>
              Failed: {job.lesson_failures.map(f => f.title).join(', ')}
            </p>
          )}
        </div>
      )}

      {job?.status === 'FAILED' && (
        <p className={styles.error} role="alert">{job.error}</p>
      )}
    </div>
  );
}

function TrackItemsEditor({ trackId }) {
  const items = useStore(s => s.trackItems[trackId]) ?? [];
  const fetchTrackItems  = useStore(s => s.fetchTrackItems);
  const updateTrackItem  = useStore(s => s.updateTrackItem);
  const deleteTrackItem  = useStore(s => s.deleteTrackItem);
  const showToast        = useStore(s => s.showToast);
  const [dragId, setDragId] = useState(null);

  useEffect(() => { fetchTrackItems(trackId); }, [trackId, fetchTrackItems]);

  async function handleDrop(targetItem) {
    if (dragId == null || dragId === targetItem.id) return;
    try {
      await updateTrackItem(trackId, dragId, { position: targetItem.position });
    } catch (e) {
      showToast(`Couldn't reorder: ${e.message ?? e}`);
    } finally {
      setDragId(null);
    }
  }

  function renderItemRow(item) {
    return (
      <li
        key={item.id}
        className={styles.itemRow}
        draggable
        onDragStart={() => setDragId(item.id)}
        onDragOver={e => e.preventDefault()}
        onDrop={() => handleDrop(item)}
      >
        <span className={styles.dragHandle} aria-hidden="true">⠿</span>
        <span className={`${styles.itemTitle} ${item.status === 'done' ? styles.itemDone : ''}`}>
          {item.title}
        </span>
        <ItemTypeChip kind={itemKind(item)} />
        <Button
          variant="ghost"
          size="sm"
          aria-label={`Remove ${item.title}`}
          onClick={() => deleteTrackItem(trackId, item.id)}
        >
          ×
        </Button>
      </li>
    );
  }

  return (
    <div className={styles.stack}>
      <h3 className={styles.sectionTitle}>Items</h3>
      <TrackAddBox trackId={trackId} />

      <ul className={styles.itemList}>
        {groupForDisplay(items).map(row => row.type === 'group' ? (
          <li key={`group-${row.groupId}`}>
            <Disclosure title={`${row.members.length} items from one source`}>
              <ul className={styles.itemList}>
                {row.members.map(renderItemRow)}
              </ul>
            </Disclosure>
          </li>
        ) : renderItemRow(row.item))}
        {items.length === 0 && <li className={styles.muted}>No items yet.</li>}
      </ul>
    </div>
  );
}

function TrackScheduleEditor({ trackId }) {
  const fetchTrackSchedule = useStore(s => s.fetchTrackSchedule);
  const updateSchedule     = useStore(s => s.updateSchedule);
  const showToast          = useStore(s => s.showToast);
  const [budgets, setBudgets] = useState({}); // weekday(string) -> budget or '' (off)

  useEffect(() => {
    fetchTrackSchedule(trackId).then(sched => {
      const next = {};
      WEEKDAYS.forEach((_, i) => { next[i] = sched?.[i] ?? ''; });
      setBudgets(next);
    });
  }, [trackId, fetchTrackSchedule]);

  function toggleDay(i) {
    setBudgets(s => ({ ...s, [i]: s[i] === '' ? 1 : '' }));
  }

  function setBudget(i, value) {
    setBudgets(s => ({ ...s, [i]: value }));
  }

  async function save() {
    const payload = {};
    Object.entries(budgets).forEach(([i, v]) => { if (v !== '' && Number(v) > 0) payload[i] = Number(v); });
    try {
      await updateSchedule(trackId, payload);
    } catch (e) {
      showToast(`Couldn't save schedule: ${e.message ?? e}`);
    }
  }

  return (
    <div className={styles.stack}>
      <h3 className={styles.sectionTitle}>Weekly schedule</h3>
      <div className={styles.weekRow}>
        {WEEKDAYS.map((label, i) => (
          <div key={i} className={styles.dayCell}>
            <Button
              size="sm"
              variant={budgets[i] !== '' && budgets[i] != null ? 'primary' : 'secondary'}
              aria-pressed={budgets[i] !== '' && budgets[i] != null}
              onClick={() => toggleDay(i)}
            >
              {label}
            </Button>
            {budgets[i] !== '' && budgets[i] != null && (
              <Input
                type="number"
                min={1}
                aria-label={`${label} items per day`}
                value={budgets[i]}
                onChange={e => setBudget(i, e.target.value)}
              />
            )}
          </div>
        ))}
      </div>
      <div className={styles.actionsRow}>
        <Button onClick={save}>Save schedule</Button>
      </div>
    </div>
  );
}
